package jkit.jil.stages;

import java.lang.reflect.Modifier;
import java.util.*;

import jkit.jil.tree.*;
import jkit.compiler.MethodNotFoundException;
import jkit.util.*;

/**
 * This stage adds "bypass" methods to a class when they are needed. A typical
 * situation they arise is the following:
 * 
 * <pre>
 * class StringIterator implements Iterator&lt;String&gt; {
 *   public boolean hasNext() { ... }
 *   public String next() { ... }
 *   public void remove() { ... }	
 * }
 * </pre>
 * 
 * When compiling this class, a method called "next" with type ()->String is
 * generated. However, when accessing <code>StringIterator</code> objects via
 * the <code>Iterator</code> interface, the expected method is
 * next:()->Object. This causes a problem as the JVM dynamic dispatch will not
 * be able to find the appropriate method. Therefore, in such situations, we
 * insert an additional method of type next:()->Object. This simply redirects
 * the original method (hence the term "bypass method").
 * 
 * @author djp
 * 
 */
public class BypassMethods {
	public String description() {
		return "Add bypasses for generic methods where they are needed";
	}

	/**
	 * Apply this pass to a class.
	 * 
	 * @param owner
	 *            class to manipulate
	 */
	public void apply(JilClass owner) {
		HashSet<Triple<JilClass, JilMethod, Type.Function>> matches = new HashSet();

		// First, we identify all the problem cases.
		for (JilMethod m : owner.methods()) {
			// Look through interfaces
			for (Type.Clazz i : owner.interfaces()) {
				checkForProblem(m, i, matches);
			}
			// Now, look through the super class (if there is one)
			if (owner.superClass() != null) {
				checkForProblem(m, owner.superClass(), matches);
			}
		}

		// Second, we add appropriate bypass methods.
		for (Triple<JilClass, JilMethod, Type.Function> p : matches) {
			JilMethod m = generateBypass(p.first(), p.second(), p.third());
			owner.methods().add(m);
		}
	}

	/**
	 * This method looks for matching methods in the owner, and its
	 * super-classes / interfaces. If a matching method is found, then it checks
	 * whether any of its types are generic or not. If they are generic, then
	 * this method is identified as a problem case and added to the set of
	 * problem cases being built.
	 * 
	 * @param method
	 *            The method which we a looking to find a match for.
	 * @param owner
	 *            The class in which to search for matching methods.
	 * @param problems
	 *            The set of problem cases being built up
	 */
	protected void checkForProblem(JilMethod method, Type.Reference owner,
			Set<Triple<JilClass, JilMethod, Type.Function>> problems) {
		try {
			// See if method m is defined in an interface implemented by
			// this class.
			Triple<JilClass, JilMethod, Type.Function> minfo = ClassTable
					.resolveMethod(owner, method.name(), Arrays.asList(method
							.type().parameterTypes()));
			Type.Function ft = minfo.second().type();
			Type.Function mt = method.type();
			Type[] ftParamTypes = ft.parameterTypes();
			Type[] mtParamTypes = mt.parameterTypes();

			boolean isMatch = ft.returnType() instanceof Type.Variable;
			for (int i = 0; i != ftParamTypes.length; ++i) {
				Type fp = ftParamTypes[i];
				Type mp = mtParamTypes[i];
				// Basically, if the parameter type we found is generic, but the
				// actual type is not, then we found a problem case.
				isMatch = isMatch
						| (fp instanceof Type.Variable && !(mp instanceof Type.Variable));
			}

			if (isMatch) {
				problems.add(new Triple(minfo.first(), minfo.second(), method
						.type()));
			}
		} catch (ClassNotFoundException ce) {
		} catch (MethodNotFoundException me) {
		}
	}

	/**
	 * Generate the bypass method with the same type as the originating method
	 * (parameter 2) which redirects to a method of type "to". Type variables
	 * found in the original methods type are replaced with java.lang.Object or
	 * their lower bounds (if they have them).
	 * 
	 * @param owner
	 *            the owner of the original, generic method
	 * @param method
	 *            the original generic method
	 * @param to
	 *            the (concrete) instantiation of the generic method
	 * @return
	 */
	protected JilMethod generateBypass(JilClass owner, JilMethod method,
			Type.Function to) {
		// First, we substitute each type variable with java.lang.object
		Type.Function from = method.type();
		Type.Function ftype = (Type.Function) stripGenerics(from);
		String name = method.name();

		// Second, create the local variable list for the new method
		ArrayList<Pair<String, List<Modifier>>> params = new ArrayList();
		ArrayList<JilExpr> funParams = new ArrayList<JilExpr>();
		List<Type> ftypeParamTypes = ftype.parameterTypes();

		for (int i = 0; i != ftypeParamTypes.size(); ++i) {
			Type t = ftypeParamTypes.get(i);
			String n = "param$" + i;
			ArrayList<Modifier> mods = new ArrayList<Modifier>();
			mods.add(new Modifier.Base(java.lang.reflect.Modifier.FINAL));
			params.add(new Pair(n, mods));
			if (from.parameterTypes().get(i) instanceof Type.Variable) {
				// the following cast is required because generic types are
				// enforced at compile time, but not strongly enforced in the
				// bytecode. Assuming the class passed compilation without any
				// warnings with respect to generic types, then this cast should
				// always pass.
				funParams.add(new JilExpr.Cast(new JilExpr.Variable(n, t), to
						.parameterTypes().get(i)));
			} else {
				funParams.add(new JilExpr.Variable(n, t));
			}
		}

		ArrayList<JilStmt> body = new ArrayList<JilStmt>();
		
		if (ftype.returnType() instanceof Type.Void) {
			// no return type
			JilStmt ivk = new JilExpr.Invoke(new JilExpr.Variable("this", owner.type()),
					name, funParams, to, ftype.returnType());
			body.add(ivk);
		} else {
			JilExpr ivk = new JilExpr.Invoke(new JilExpr.Variable("this", owner.type()),
					name, funParams, to, ftype.returnType());
			JilStmt ret = new JilStmt.Return(ivk);
			body.add(ret);
		}

		return new JilMethod(Modifier.PUBLIC, ftype, name,
				new ArrayList<Type.Reference>(), null, body);
	}

	/**
	 * This method simply strips off any generic variables and replaces them
	 * with java.lang.Object. For example, the following types:
	 * 
	 * <pre>
	 * T next()
	 * void add(List&lt;T&gt; x, T y)
	 * &lt;T extends Number&gt; void add(List&lt;T&gt; x, T y)
	 * </pre>
	 * 
	 * would become:
	 * 
	 * <pre>
	 * java.lang.Object next()
	 * void add(List x, java.lang.Object y)
	 * void add(List x, java.lang.Number y)
	 * </pre>
	 * 
	 * Observe here that, in the second add, the second parameter goes to Number
	 * because of the type bound.
	 * 
	 * @param type -
	 *            the type to be stripped
	 * @param method -
	 *            the enclosing method of the type
	 * @param owner -
	 *            the enclosing class of the type
	 * @return
	 */
	public static Type stripGenerics(Type type) {
		if (type instanceof Type.Primitive || type instanceof Type.Null
				|| type instanceof Type.Any || type instanceof Type.Void) {
			return type;
		} else if (type instanceof Type.Variable) {
			Type.Variable tv = (Type.Variable) type;
			Type lb[] = tv.lowerBounds();
			if (lb.length > 0) {
				return stripGenerics(lb[0]);
			} else {
				return Type.referenceType("java.lang", "Object");
			}
		} else if (type instanceof Type.Array) {
			Type.Array at = (Type.Array) type;
			return Type.arrayType(stripGenerics(at.elementType()), at
					.elements());
		} else if (type instanceof Type.Reference) {
			Type.Reference tr = (Type.Reference) type;
			Pair<String, Type[]>[] trClasses = tr.classes();
			Pair<String, Type[]>[] classes = new Pair[trClasses.length];
			for (int i = 0; i != trClasses.length; ++i) {
				classes[i] = new Pair(trClasses[i].first(), new Type[0]);
			}
			return Type.referenceType(tr.pkg(), classes, type.elements());
		} else if (type instanceof Type.Function) {
			Type.Function tf = (Type.Function) type;
			Type retType = stripGenerics(tf.returnType());
			Type[] tfParamTypes = tf.parameterTypes();
			Type[] paramTypes = new Type[tfParamTypes.length];
			for (int i = 0; i != tfParamTypes.length; ++i) {
				paramTypes[i] = stripGenerics(tfParamTypes[i]);
			}
			return Type.functionType(retType, paramTypes, new Type.Variable[0],
					type.elements());
		} else {
			throw new InternalException(
					"Type \"" + type + "\" not recognised.", null, null, null);
		}
	}
}
