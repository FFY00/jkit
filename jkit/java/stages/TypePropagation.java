package jkit.java.stages;

import java.util.*;

import jkit.compiler.ClassLoader;
import jkit.compiler.FieldNotFoundException;
import jkit.compiler.MethodNotFoundException;
import jkit.compiler.SyntaxError;
import jkit.java.*;
import jkit.java.io.JavaFile;
import jkit.java.tree.Decl;
import jkit.java.tree.Expr;
import jkit.java.tree.Stmt;
import jkit.java.tree.Value;
import jkit.java.tree.Decl.*;
import jkit.java.tree.Stmt.Case;
import jkit.util.*;
import jkit.jil.tree.Modifier;
import jkit.jil.tree.SourceLocation;
import jkit.jil.tree.SyntacticElement;
import jkit.jil.tree.Type;

/**
 * The purpose of this operation, is to propagate type information throughout
 * the expressions and statements of the Javafile. The key challenge is that, in
 * many places, we must apply rules from the Java Language Spec to determine
 * what the resulting type. For example, in the context of a binary expression
 * (e.g. +) if the types of the left and right operands differ, we must
 * carefully determine the resulting type.
 * 
 * This operation also introduces "Convert" objects in places to avoid future
 * ambiguity. For example, if we have a binary operation whose left and
 * right-hand operand types differ then, under the JLS, we may need to apply an
 * up-conversion (e.g. int -> long) to the parameter before the operation. The
 * Convert objects introduced thus capture the situations where such conversions
 * are required; this helps later on, since we don't have to repeat the working
 * to determine where a conversion is needed.
 * 
 * @author djp
 * 
 */
public class TypePropagation {
	private ClassLoader loader;
	private TypeSystem types;
	private Stack<JavaClass> scopes = new Stack<JavaClass>();
	
	public TypePropagation(ClassLoader loader, TypeSystem types) {
		this.loader = loader; 
		this.types = types;
	}
	
	public void apply(JavaFile file) {
		for(Decl d : file.declarations()) {
			doDeclaration(d);
		}
	}
	
	protected void doDeclaration(Decl d) {
		if(d instanceof JavaInterface) {
			doInterface((JavaInterface)d);
		} else if(d instanceof JavaClass) {
			doClass((JavaClass)d);
		} else if(d instanceof JavaMethod) {
			doMethod((JavaMethod)d);
		} else if(d instanceof JavaField) {
			doField((JavaField)d);
		} else if (d instanceof Decl.InitialiserBlock) {
			doInitialiserBlock((Decl.InitialiserBlock) d);
		} else if (d instanceof Decl.StaticInitialiserBlock) {
			doStaticInitialiserBlock((Decl.StaticInitialiserBlock) d);
		} else {
			syntax_error("internal failure (unknown declaration \"" + d
					+ "\" encountered)",d);
		}
	}
	
	protected void doInterface(JavaInterface d) {
		doClass(d);
	}
	
	protected void doClass(JavaClass c) {
		scopes.push(c);
		
		for(Decl d : c.declarations()) {
			doDeclaration(d);
		}
		
		scopes.pop();
	}

	protected void doMethod(JavaMethod d) {
		doStatement(d.body(),d);
	}

	protected void doField(JavaField d) {
		Expr init = d.initialiser();
		Type type = (Type) d.type().attribute(Type.class);
		
		// special case for dealing with array values.
		// perform type inference (if necesssary)
		if(init != null) {
			if(isUnknownConstant(init)) {			
				Expr c = unknownConstantInference(init, type,
						(SourceLocation) init
						.attribute(SourceLocation.class));
				d.setInitialiser(c);
			} else if(init instanceof Value.Array) {
				doArrayVal(type,(Value.Array) init);
			} else {
				doExpression(init);			
				d.setInitialiser(implicitCast(init,type));					
			}
			
		}
	}
	
	protected void doInitialiserBlock(Decl.InitialiserBlock d) {
		// will need to add code here for dealing with classes nested in
		// methods.
		for (Stmt s : d.statements()) {
			doStatement(s, null);
		}
	}
	
	protected void doStaticInitialiserBlock(Decl.StaticInitialiserBlock d) {
		// will need to add code here for dealing with classes nested in
		// methods.
		for (Stmt s : d.statements()) {
			doStatement(s, null);
		}
	}
	
	protected void doStatement(Stmt e, JavaMethod m) {
		if(e instanceof Stmt.SynchronisedBlock) {
			doSynchronisedBlock((Stmt.SynchronisedBlock)e, m);
		} else if(e instanceof Stmt.TryCatchBlock) {
			doTryCatchBlock((Stmt.TryCatchBlock)e, m);
		} else if(e instanceof Stmt.Block) {
			doBlock((Stmt.Block)e, m);
		} else if(e instanceof Stmt.VarDef) {
			doVarDef((Stmt.VarDef) e, m);
		} else if(e instanceof Stmt.Assignment) {
			doAssignment((Stmt.Assignment) e, m);
		} else if(e instanceof Stmt.Return) {
			doReturn((Stmt.Return) e, m);
		} else if(e instanceof Stmt.Throw) {
			doThrow((Stmt.Throw) e, m);
		} else if(e instanceof Stmt.Assert) {
			doAssert((Stmt.Assert) e, m);
		} else if(e instanceof Stmt.Break) {
			doBreak((Stmt.Break) e, m);
		} else if(e instanceof Stmt.Continue) {
			doContinue((Stmt.Continue) e, m);
		} else if(e instanceof Stmt.Label) {
			doLabel((Stmt.Label) e, m);
		} else if(e instanceof Stmt.If) {
			doIf((Stmt.If) e, m);
		} else if(e instanceof Stmt.For) {
			doFor((Stmt.For) e, m);
		} else if(e instanceof Stmt.ForEach) {
			doForEach((Stmt.ForEach) e, m);
		} else if(e instanceof Stmt.While) {
			doWhile((Stmt.While) e, m);
		} else if(e instanceof Stmt.DoWhile) {
			doDoWhile((Stmt.DoWhile) e, m);
		} else if(e instanceof Stmt.Switch) {
			doSwitch((Stmt.Switch) e, m);
		} else if(e instanceof Expr.Invoke) {
			doInvoke((Expr.Invoke) e);
		} else if(e instanceof Expr.New) {
			doNew((Expr.New) e);
		} else if(e instanceof Decl.JavaClass) {
			doClass((Decl.JavaClass)e);
		} else if(e != null) {
			syntax_error("Internal failure (invalid statement \""
					+ e.getClass() + "\" encountered)", e);			
		}		
	}
	
	protected void doBlock(Stmt.Block block, JavaMethod m) {
		if(block != null) {			
			// now process every statement in this block.
			for(Stmt s : block.statements()) {
				doStatement(s, m);
			}
		}
	}
	
	protected void doSynchronisedBlock(Stmt.SynchronisedBlock block, JavaMethod m) {
		doBlock(block,m);
		doExpression(block.expr());
	}
	
	protected void doTryCatchBlock(Stmt.TryCatchBlock block, JavaMethod m) {
		doBlock(block,m);
		doBlock(block.finaly(),m);

		for (Stmt.CatchBlock cb : block.handlers()) {			
			doBlock(cb,m);
		}
	}
	
	protected void doVarDef(Stmt.VarDef def, JavaMethod m) {
		Type t = (Type) def.type().attribute(Type.class);
		
		List<Triple<String, Integer, Expr>> defs = def.definitions();
		for(int i=0;i!=defs.size();++i) {
			Triple<String, Integer, Expr> d = defs.get(i);			
			
			// calculate the actual type of this variable.
			Type nt = t;						
			for(int j=0;j!=d.second();++j) {
				nt = new Type.Array(nt);
			}						
			
			// perform type inference (if necesssary)
			if(d.third() != null) {
				if(isUnknownConstant(d.third())) {
					Expr c = unknownConstantInference(d.third(), nt,
							(SourceLocation) d.third
							.attribute(SourceLocation.class));
					defs.set(i, new Triple(d.first(), d.second(), c));
				} else if(d.third() instanceof Value.Array) {
					doArrayVal(nt,(Value.Array) d.third());
				} else {
					doExpression(d.third());
					defs.set(i, new Triple(d.first(), d.second(), implicitCast(
							d.third(), nt)));
				}
				
			}
		}
	}
	
	protected void doAssignment(Stmt.Assignment def, JavaMethod m) {
		doExpression(def.lhs());	
		doExpression(def.rhs());			

		Type lhs_t = (Type) def.lhs().attribute(Type.class);
		
		def.setRhs(implicitCast(def.rhs(),lhs_t));				
		
		// perform type inference (if necesssary)
		if(isUnknownConstant(def.rhs())) {
			Expr c = unknownConstantInference(def.rhs(), lhs_t,
					(SourceLocation) def.rhs()
							.attribute(SourceLocation.class));
			
			def.setRhs(c);			
		}
		
		def.attributes().add(lhs_t);
	}
	
	protected void doReturn(Stmt.Return ret, JavaMethod m) {
		if(ret.expr() != null) {
			// We need to do an implict cast here to account for autoboxing, and
			// other conversions. For example, a method declared to return
			// Integer that actually returns "1" must box this at the point of
			// return.
			doExpression(ret.expr());
			ret.setExpr(implicitCast(ret.expr(), (Type) m.returnType()
					.attribute(Type.class)));
		}
	}
	
	protected void doThrow(Stmt.Throw ret, JavaMethod m) {
		doExpression(ret.expr());
	}
	
	protected void doAssert(Stmt.Assert ret, JavaMethod m) {
		doExpression(ret.expr());
	}
	
	protected void doBreak(Stmt.Break brk, JavaMethod m) {
		// nothing	
	}
	
	protected void doContinue(Stmt.Continue brk, JavaMethod m) {
		// nothing
	}
	
	protected void doLabel(Stmt.Label lab, JavaMethod m) {						
		doStatement(lab.statement(),m);
	}
	
	protected void doIf(Stmt.If stmt, JavaMethod m) {
		doExpression(stmt.condition());
		stmt.setCondition(implicitCast(stmt.condition(), new Type.Bool()));
		doStatement(stmt.trueStatement(),m);
		doStatement(stmt.falseStatement(),m);
	}
	
	protected void doWhile(Stmt.While stmt, JavaMethod m) {
		doExpression(stmt.condition());
		stmt.setCondition(implicitCast(stmt.condition(), new Type.Bool()));
		doStatement(stmt.body(),m);		
	}
	
	protected void doDoWhile(Stmt.DoWhile stmt, JavaMethod m) {
		doExpression(stmt.condition());
		stmt.setCondition(implicitCast(stmt.condition(), new Type.Bool()));
		doStatement(stmt.body(),m);
	}
	
	protected void doFor(Stmt.For stmt, JavaMethod m) {		
		doStatement(stmt.initialiser(),m);
		doExpression(stmt.condition());		
		stmt.setCondition(implicitCast(stmt.condition(), new Type.Bool()));
		doStatement(stmt.increment(),m);
		doStatement(stmt.body(),m);	
	}
	
	protected void doForEach(Stmt.ForEach stmt, JavaMethod m) {
		doExpression(stmt.source());
		doStatement(stmt.body(),m);
	}
	
	protected void doSwitch(Stmt.Switch sw, JavaMethod m) {
		doExpression(sw.condition());
		for(Case c : sw.cases()) {
			doExpression(c.condition());
			for(Stmt s : c.statements()) {
				doStatement(s,m);
			}
		}
		
		// should check that case conditions are final constants here.
	}
	
	protected void doExpression(Expr e) {	
		if(e instanceof Value.Bool) {
			doBoolVal((Value.Bool)e);
		} else if(e instanceof Value.Char) {
			doCharVal((Value.Char)e);
		} else if(e instanceof Value.Int) {
			doIntVal((Value.Int)e);
		} else if(e instanceof Value.Long) {
			doLongVal((Value.Long)e);
		} else if(e instanceof Value.Float) {
			doFloatVal((Value.Float)e);
		} else if(e instanceof Value.Double) {
			doDoubleVal((Value.Double)e);
		} else if(e instanceof Value.String) {
			doStringVal((Value.String)e);
		} else if(e instanceof Value.Null) {
			doNullVal((Value.Null)e);
		} else if(e instanceof Value.TypedArray) {
			doTypedArrayVal((Value.TypedArray)e);
		} else if(e instanceof Value.Class) {
			doClassVal((Value.Class) e);
		} else if(e instanceof Expr.LocalVariable) {
			doLocalVariable((Expr.LocalVariable)e);
		} else if(e instanceof Expr.NonLocalVariable) {
			doNonLocalVariable((Expr.NonLocalVariable)e);
		} else if(e instanceof Expr.ClassVariable) {
			doClassVariable((Expr.ClassVariable)e);
		} else if(e instanceof Expr.UnOp) {
			doUnOp((Expr.UnOp)e);
		} else if(e instanceof Expr.BinOp) {
			doBinOp((Expr.BinOp)e);
		} else if(e instanceof Expr.TernOp) {
			doTernOp((Expr.TernOp)e);
		} else if(e instanceof Expr.Cast) {
			doCast((Expr.Cast)e);
		} else if(e instanceof Expr.InstanceOf) {
			doInstanceOf((Expr.InstanceOf)e);
		} else if(e instanceof Expr.Invoke) {
			doInvoke((Expr.Invoke) e);
		} else if(e instanceof Expr.New) {
			doNew((Expr.New) e);
		} else if(e instanceof Expr.ArrayIndex) {
			doArrayIndex((Expr.ArrayIndex) e);
		} else if(e instanceof Expr.Deref) {
			doDeref((Expr.Deref) e);
		} else if(e instanceof Stmt.Assignment) {
			// force brackets			
			doAssignment((Stmt.Assignment) e, null);			
		} else if(e != null) {
			syntax_error("Internal failure (invalid expression \"" + e.getClass() + "\" encountered)",e);			
		}
	}
	
	protected void doDeref(Expr.Deref e) {		
		doExpression(e.target());	
		
		Type tmp = (Type) e.target().attribute(Type.class);
		
		if(!(tmp instanceof Type.Reference)) {
			syntax_error("cannot dereference type: " + tmp,e);
		} else if(tmp instanceof Type.Array) {
			// This dereference must represent an internal method access.
			if(e.name().equals("length")) {
				// most common case.
				e.attributes().add(new Type.Int());
			} else {
				syntax_error("field not found: " + tmp + "." + e.name(),e);
			}
		} else {

			Type.Clazz target = (Type.Clazz) tmp;

			if(e.name().equals("this")) {
				// This is a special case, where we're trying to look up a field
				// called "this". No such field can exist! What this means is that
				// we're inside an inner class, and we're trying to access the this
				// pointer of an enclosing class. This is easy to deal with here,
				// since the type returned by this expression will be the target
				// type of the dereference.
				e.attributes().add(target);
			} else {
				// now, perform field lookup!
				try {
					Triple<jkit.compiler.Clazz, jkit.compiler.Clazz.Field, Type> r = types
							.resolveField(target, e.name(), loader);
					e.attributes().add(r.third());			
				} catch(ClassNotFoundException cne) {
					syntax_error("class not found: " + target,e,cne);
				} catch(FieldNotFoundException fne) {
					syntax_error("field not found: " + target + "." + e.name(),e,fne);
				}
			}
		}
	}
	
	protected void doArrayIndex(Expr.ArrayIndex e) {
		doExpression(e.target());
		doExpression(e.index());
		
		e.setIndex(implicitCast(e.index(),new Type.Int()));
				
		Type target_t = (Type) e.target().attribute(Type.class);
		
		if(target_t instanceof Type.Array) {
			Type.Array at = (Type.Array) target_t;
			e.attributes().add(at.element());
		} else {
			// this is really a syntax error
			syntax_error("array required, but " + target_t + " found", e);
		}
	}
	
	protected void doNew(Expr.New e) {
		// First, figure out the type being created.		
		Type t = (Type) e.type().attribute(Type.class);
		e.attributes().add(t);
		
		// Second, recurse through any parameters supplied ...
		ArrayList<Type> parameterTypes = new ArrayList<Type>();
		
		for(Expr p : e.parameters()) {
			doExpression(p);
			parameterTypes.add((Type) p.attribute(Type.class));
		}
		
		if(t instanceof Type.Clazz) {
			Type.Clazz tc = (Type.Clazz) t;
			try {
				String constructorName = tc.components().get(
						tc.components().size() - 1).first();
				Triple<jkit.compiler.Clazz, jkit.compiler.Clazz.Method, Type.Function> r = types
						.resolveMethod(tc, constructorName, parameterTypes,
								loader);
				Type.Function f = r.third();
				
				// At this stage, we have (finally) figured out what method is to be
				// called. There are a few things that remain to be done, however.
				// Firstly, we must add any implicitCasts that are required for
				// boxing conversions.  
					
				List<Expr> e_parameters = e.parameters();
				List<Type> ft_parameters = f.parameterTypes();
				for (int i = 0; i != e_parameters.size(); ++i) {
					Type pt = ft_parameters.get(i);
					e_parameters.set(i, implicitCast(e_parameters.get(i), pt));
				}
				
				e.attributes().add(r.second().type()); // this is a little hacky, but it works.
				
			} catch(ClassNotFoundException cnfe) {
				syntax_error(cnfe.getMessage(), e, cnfe);
			} catch(MethodNotFoundException mfne) {
				String msg = "constructor not found: " + tc + "(";
				boolean firstTime = true;
				for (Type pt : parameterTypes) {
					if (!firstTime) {
						msg += ", ";
					}
					firstTime = false;
					msg += pt;
				}
				syntax_error(msg + ")", e, mfne);
			} catch(TypeSystem.BindError be) {
				// This can happen if the parameters supplied to bind, which is
				// called by resolveMethod are somehow not "base equivalent"
				syntax_error(be.getMessage(),e,be);
			} catch(Exception be) {
				// General catch all. The reason for having it is so we can
				// attribute the cause of the internal failure with a line number.
				syntax_error("internal failure (" + be.getMessage() + ")",e,be);
			} 
		} else if(t instanceof Type.Array) {
			// need to do something here also ...
		}
		
		// Third, check whether this is constructing an anonymous class ...
		for(Decl d : e.declarations()) {
			doDeclaration(d);
		}
	}
	
	protected void doInvoke(Expr.Invoke e) {
		ArrayList<Type> parameterTypes = new ArrayList<Type>();
		
		doExpression(e.target());
		
		for(Expr p : e.parameters()) {
			doExpression(p);
			parameterTypes.add((Type) p.attribute(Type.class));
		}
		
		// Now, to determine the return type of this method, we need to lookup
		// the method in the class hierarchy. This lookup procedure is seriously
		// non-trivial, and is implemented in the TypeSystem module.
			
		Type.Reference receiver = null;
		String e_name = e.name();
		
		try {		
			if(e.name().equals("super") || e.name().equals("this")) {				
				Type.Clazz r = (Type.Clazz) e.attribute(Type.class);				
				e_name = r.components().get(r.components().size() - 1).first();
				receiver = r;
			} else {
				Type rt = (Type) e.target().attribute(Type.class);
				
				if(rt instanceof Type.Variable) {
					// in this situation, we're trying to dereference a generic
					// variable. Therefore, we choose the largest type which
					// this could possibly, and assume the receiver is this type.
					Type.Variable vt = (Type.Variable) rt;										
					
					if(vt.lowerBound() != null) {
						receiver = vt.lowerBound(); 
					} else {						
						receiver = new Type.Clazz("java.lang","Object");
					}
				} else {
					receiver = (Type.Clazz) e.target().attribute(Type.class);
				}
			}							
			
			Triple<jkit.compiler.Clazz, jkit.compiler.Clazz.Method, Type.Function> r = types
					.resolveMethod(receiver, e_name, parameterTypes, loader);
			Type.Function f = r.third();
			
			// At this stage, we have (finally) figured out what method is to be
			// called. There are a few things that remain to be done, however.
			// Firstly, we must add any implicitCasts that are required for
			// boxing conversions.  
				
			List<Expr> e_parameters = e.parameters();
			List<Type> ft_parameters = f.parameterTypes();
			for (int i = 0; i != e_parameters.size(); ++i) {
				Type pt = ft_parameters.get(i);
				e_parameters.set(i, implicitCast(e_parameters.get(i), pt));
			}
			
			// Secondly, we must add type information to the expression.
			
			if (!(f.returnType() instanceof Type.Void)) {
				e.attributes().add(f.returnType());
			}
			
			e.attributes().add(r.second().type()); // this is a little hacky, but it works.
		} catch(ClassNotFoundException cnfe) {
			syntax_error(cnfe.getMessage(), e, cnfe);
		} catch(MethodNotFoundException mfne) {
			String msg = "method not found: " + receiver + "." + e_name + "(";
			boolean firstTime = true;
			for (Type t : parameterTypes) {
				if (!firstTime) {
					msg += ", ";
				}
				firstTime = false;
				msg += t;
			}
			syntax_error(msg + ")", e, mfne);
		} catch(TypeSystem.BindError be) {
			// This can happen if the parameters supplied to bind, which is
			// called by resolveMethod are somehow not "base equivalent"
			syntax_error(be.getMessage(),e,be);
		} catch(Exception be) {
			// General catch all. The reason for having it is so we can
			// attribute the cause of the internal failure with a line number.
			syntax_error("internal failure (" + be.getMessage() + ")",e,be);
		} 
	}
	
	protected void doInstanceOf(Expr.InstanceOf e) {
		doExpression(e.lhs());
		e.attributes().add(new Type.Bool());
	}
	
	protected void doCast(Expr.Cast e) {
		Type ct = (Type) e.type().attribute(Type.class);
		doExpression(e.expr());
		// the implicit cast is required to deal with boxing/unboxing (amongst
		// other things?)
		e.setExpr(implicitCast(e.expr(),ct));
		e.attributes().add(ct);
	}
	
	protected void doBoolVal(Value.Bool e) {
		e.attributes().add(new Type.Bool());
	}
	
	protected void doCharVal(Value.Char e) {
		e.attributes().add(new Type.Char());
	}
	
	protected void doIntVal(Value.Int e) {
		e.attributes().add(new Type.Int());
	}
	
	protected void doLongVal(Value.Long e) {		
		e.attributes().add(new Type.Long());
	}
	
	protected void doFloatVal(Value.Float e) {		
		e.attributes().add(new Type.Float());
	}
	
	protected void doDoubleVal(Value.Double e) {		
		e.attributes().add(new Type.Double());
	}
	
	protected void doStringVal(Value.String e) {		
		e.attributes().add(new Type.Clazz("java.lang","String"));
	}
	
	protected void doNullVal(Value.Null e) {		
		e.attributes().add(new Type.Null());
	}
	
	protected void doTypedArrayVal(Value.TypedArray e) {		
		Type _type = (Type) e.type().attribute(Type.class);
		if(!(_type instanceof Type.Array)) {
			syntax_error("cannot assign array value to type " + _type,e);
		}		
		Type.Array type = (Type.Array) _type;
		
		for(int i=0;i!=e.values().size();++i) {
			Expr v = e.values().get(i);
			if(v instanceof Value.Array) {
				Type.Array ta = (Type.Array) type;
				doArrayVal(ta,(Value.Array)v);
			} else if (isUnknownConstant(v)) {
				v = unknownConstantInference(v, type.element(),
						(SourceLocation) v.attribute(SourceLocation.class));				
			} else {
				doExpression(v);				
			}			
			e.values().set(i,implicitCast(v,type.element()));
		}

		e.attributes().add(type);
	}
	
	
	/**
	 * Dealing with Array Value's requireas a special method, where the result
	 * type of the ArrayVal is known. This is important since the type of an
	 * array initialiser is actually computed from the type of the
	 * variable/field/... that it's being assigned to. For example, in the
	 * following:
	 * <p>
	 * 
	 * <pre>
	 * Object[] x = { 1, 2, 3 };
	 * </pre>
	 * 
	 * </p>
	 * 
	 * the type of ArrayVal must be Object[], not int[] (which would otherwise
	 * be inferred).
	 * 
	 * @param type
	 *            --- type to cast the lhs into
	 * @param e
	 *            --- Array expression
	 * @return
	 */
	protected void doArrayVal(Type _lhs, Value.Array e) {		
		if(!(_lhs instanceof Type.Array)) {
			syntax_error("cannot assign array value to type " + _lhs,e);
		}		
		Type.Array lhs = (Type.Array)_lhs;
		for(int i=0;i!=e.values().size();++i) {
			Expr v = e.values().get(i);
			if(v instanceof Value.Array) {
				Type.Array ta = (Type.Array) lhs;
				doArrayVal(ta,(Value.Array)v);
			} else if(isUnknownConstant(v)) {			
				v = unknownConstantInference(v, lhs.element(),
						(SourceLocation) v.attribute(SourceLocation.class));										
			} else {
				doExpression(v);				
			}					
			e.values().set(i,implicitCast(v,lhs.element()));
		}
		
		e.attributes().add(lhs);
	}
	
	protected void doClassVal(Value.Class e) {
		// Basically, this corresponds to some code like this:
		// <pre>
		// void f() {
		// String x = String.class.getName();
		// }
		// <pre>
		// Here, the Class Value is that returned by "String.class" and it
		// corresponds to an instance of java.lang.Class<String>. Therefore, we
		// need to construct a type representing java.lang.Class<X> here.
		
		Type.Clazz c = (Type.Clazz) e.value().attribute(Type.class);
		List<Type.Reference> tvars = new ArrayList();
		tvars.add(c);
		List<Pair<String, List<Type.Reference>>> components = new ArrayList();
		components.add(new Pair("Class",tvars));
		e.attributes().add(new Type.Clazz("java.lang",components));
	}
	
	protected void doLocalVariable(Expr.LocalVariable e) {
		// don't need to do anything here --- ScopeResolution has initialised
		// the type of all variable accesses.
	}

	protected void doNonLocalVariable(Expr.NonLocalVariable e) {
		// don't need to do anything here --- ScopeResolution has initialised
		// the type of all variable accesses.
	}
	
	protected void doClassVariable(Expr.ClassVariable e) {
		// don't need to do anything here --- ScopeResolution has initialised
		// the type of all variable accesses.		
	}
	
	protected void doUnOp(Expr.UnOp e) {		
		doExpression(e.expr());
		Type expr_t = (Type) e.expr().attribute(Type.class);
		
		switch(e.op()) {
		case Expr.UnOp.NEG:
			if (expr_t instanceof Type.Byte || expr_t instanceof Type.Char
					|| expr_t instanceof Type.Short) {
				// This is a strange feature of javac. I don't really understand
				// why it's necessary.
				e.setExpr(implicitCast(e.expr(),new Type.Int()));
				e.attributes().add(new Type.Int());				
			} else {
				e.attributes().add(expr_t);				
			}
			break;		
		case Expr.UnOp.INV:
			if (expr_t instanceof Type.Byte || expr_t instanceof Type.Char
					|| expr_t instanceof Type.Short) {
				// This is a strange feature of javac. I don't really understand
				// why it's necessary.
				e.setExpr(implicitCast(e.expr(),new Type.Int()));
				e.attributes().add(new Type.Int());				
			} else {
				e.attributes().add(expr_t);
			} 
			break;	
		default:
			e.attributes().add(expr_t);	
		}		
	}
		
	protected void doBinOp(Expr.BinOp e) {				
		doExpression(e.lhs());
		doExpression(e.rhs());
		
		Type lhs_t = (Type) e.lhs().attribute(Type.class);
		Type rhs_t = (Type) e.rhs().attribute(Type.class);
		
		switch(e.op()) {
			case Expr.BinOp.EQ:
			case Expr.BinOp.NEQ:
			case Expr.BinOp.LT:
			case Expr.BinOp.LTEQ:
			case Expr.BinOp.GT:
			case Expr.BinOp.GTEQ:
			{
				if ((lhs_t instanceof Type.Primitive || isWrapper(lhs_t))
						&& (rhs_t instanceof Type.Primitive || isWrapper(rhs_t))) {
					Type rt = binaryNumericPromotion(lhs_t, rhs_t, e);
					e.setLhs(implicitCast(e.lhs(), rt));
					e.setRhs(implicitCast(e.rhs(), rt));
					e.attributes().add(new Type.Bool());
				} else if (e.op() == Expr.BinOp.EQ || e.op() == Expr.BinOp.NEQ) {
					e.attributes().add(new Type.Bool());
				} else {
					syntax_error("operands have invalid types " + lhs_t + " and "
						+ rhs_t, e);
				}
				break;
			}
			case Expr.BinOp.ADD:
			case Expr.BinOp.SUB:
			case Expr.BinOp.MUL:
			case Expr.BinOp.DIV:
			case Expr.BinOp.MOD:
			{						
				if ((lhs_t instanceof Type.Primitive || isWrapper(lhs_t))
						&& (rhs_t instanceof Type.Primitive || isWrapper(rhs_t))) {
					Type rt = binaryNumericPromotion(lhs_t, rhs_t, e);
					e.setLhs(implicitCast(e.lhs(), rt));
					e.setRhs(implicitCast(e.rhs(), rt));
					e.attributes().add(rt);
				} else if (e.op() == Expr.BinOp.ADD
						&& (isString(lhs_t) || isString(rhs_t))) {
					e.attributes().add(new Type.Clazz("java.lang", "String"));
					e.setOp(Expr.BinOp.CONCAT);
				} else {
					syntax_error("operands have invalid types " + lhs_t + " and "
						+ rhs_t, e);
				}
				break;
			}
			case Expr.BinOp.SHL:
			case Expr.BinOp.SHR:
			case Expr.BinOp.USHR:
			{					
				if ((lhs_t instanceof Type.Primitive || isWrapper(lhs_t))
						&& (rhs_t instanceof Type.Primitive || isWrapper(rhs_t))) {
					Type rt_left = unaryNumericPromotion(lhs_t, e);
					e.setLhs(implicitCast(e.lhs(), rt_left));
					e.setRhs(implicitCast(e.rhs(), new Type.Int()));
					e.attributes().add(rt_left);
				} else {
					syntax_error("operands have invalid types " + lhs_t + " and " + rhs_t,e);
				}
				break;
			}
			case Expr.BinOp.LAND:
			case Expr.BinOp.LOR:
			{				
				e.setLhs(implicitCast(e.lhs(),new Type.Bool()));
				e.setRhs(implicitCast(e.rhs(),new Type.Bool()));
				e.attributes().add(new Type.Bool());
				break;
			}
			case Expr.BinOp.AND:
			case Expr.BinOp.OR:
			case Expr.BinOp.XOR:
			{								
				if ((lhs_t instanceof Type.Primitive || isWrapper(lhs_t))
						&& (rhs_t instanceof Type.Primitive || isWrapper(rhs_t))) {
					Type rt = binaryNumericPromotion(lhs_t, rhs_t, e);
					e.setLhs(implicitCast(e.lhs(),rt));
					e.setRhs(implicitCast(e.rhs(),rt));
					e.attributes().add(rt);						
				} else {
					syntax_error("operands have invalid types " + lhs_t + " and "
						+ rhs_t, e);
				}
				break;
			}					
		}
	}
	
	protected void doTernOp(Expr.TernOp e) {		
		doExpression(e.condition());
		doExpression(e.falseBranch());
		doExpression(e.trueBranch());
		
		Type lhs_t = (Type) e.trueBranch().attribute(Type.class);
		Type rhs_t = (Type) e.falseBranch().attribute(Type.class);
		
		/*
		 * See JLS Section 15.25 for more details on the rules that apply here. 
		 */
		if(lhs_t.equals(rhs_t)) {
			e.attributes().add(lhs_t);
		} else if((lhs_t instanceof Type.Bool || rhs_t instanceof Type.Bool)
				&& (isWrapper(lhs_t,"Boolean") || isWrapper(rhs_t,"Boolean"))) {
			e.attributes().add(new Type.Bool());			
		} else if(lhs_t instanceof Type.Null) {			
			e.attributes().add(rhs_t);
		} else if(rhs_t instanceof Type.Null) {
			e.attributes().add(lhs_t);			
		} else if((lhs_t instanceof Type.Byte || rhs_t instanceof Type.Byte) && 
				(lhs_t instanceof Type.Short || rhs_t instanceof Type.Short)) {
			e.attributes().add(new Type.Short());
		} else if ((lhs_t instanceof Type.Byte || isWrapper(lhs_t, "Byte"))
				&& rhs_t instanceof Type.Int
				&& isUnknownConstant(e.falseBranch())) {
			int v = evaluateUnknownConstant(e.falseBranch());
			if(v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
				e.setFalseBranch(implicitCast(e.falseBranch(),lhs_t));
				e.attributes().add(lhs_t);
				return;
			}
		} else if ((rhs_t instanceof Type.Byte || isWrapper(rhs_t, "Byte"))
				&& lhs_t instanceof Type.Int
				&& isUnknownConstant(e.trueBranch())) {
			int v = evaluateUnknownConstant(e.trueBranch());
			if(v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
				e.setTrueBranch(implicitCast(e.trueBranch(),rhs_t));
				e.attributes().add(rhs_t);
				return;
			}
		} else if ((lhs_t instanceof Type.Char || isWrapper(lhs_t, "Character"))
				&& rhs_t instanceof Type.Int
				&& isUnknownConstant(e.falseBranch())) {
			int v = evaluateUnknownConstant(e.falseBranch());
			if(v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
				e.setFalseBranch(implicitCast(e.falseBranch(),lhs_t));
				e.attributes().add(lhs_t);
				return;
			}
		} else if ((rhs_t instanceof Type.Char || isWrapper(rhs_t, "Character"))
				&& lhs_t instanceof Type.Int
				&& isUnknownConstant(e.trueBranch())) {
			int v = evaluateUnknownConstant(e.trueBranch());
			if(v >= Character.MIN_VALUE && v <= Character.MAX_VALUE) {
				e.setTrueBranch(implicitCast(e.trueBranch(),rhs_t));
				e.attributes().add(rhs_t);
				return;
			}
		} else if ((lhs_t instanceof Type.Short || isWrapper(lhs_t, "Short"))
				&& rhs_t instanceof Type.Int
				&& isUnknownConstant(e.falseBranch())) {
			int v = evaluateUnknownConstant(e.falseBranch());
			if(v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
				e.setFalseBranch(implicitCast(e.falseBranch(),lhs_t));
				e.attributes().add(lhs_t);
				return;
			}
		} else if ((rhs_t instanceof Type.Short || isWrapper(rhs_t, "Short"))
				&& lhs_t instanceof Type.Int
				&& isUnknownConstant(e.trueBranch())) {
			int v = evaluateUnknownConstant(e.trueBranch());
			if(v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
				e.setTrueBranch(implicitCast(e.trueBranch(),rhs_t));
				e.attributes().add(rhs_t);
				return;
			}
		} else if ((isWrapper(lhs_t) || isWrapper(rhs_t))
				&& (lhs_t instanceof Type.Primitive || rhs_t instanceof Type.Primitive)) {
			Type rt = binaryNumericPromotion(lhs_t,rhs_t,e);
			e.attributes().add(rt);
			e.setTrueBranch(implicitCast(e.trueBranch(),rt));
			e.setFalseBranch(implicitCast(e.falseBranch(),rt));
		} else if(lhs_t instanceof Type.Reference && rhs_t instanceof Type.Reference) {
			// At this point, we have some class types and we need to determine
			// their greatest lower bound.
			Type rt;			
			if(lhs_t instanceof Type.Clazz && rhs_t instanceof Type.Clazz) {
				try {					
					rt = types.greatestSupertype((Type.Clazz) lhs_t,
							(Type.Clazz) rhs_t, loader);					
				} catch(ClassNotFoundException cne) {
					syntax_error(cne.getMessage(),e,cne);
					return; // dead code
				}				
			} else if(lhs_t instanceof Type.Clazz || rhs_t instanceof Type.Clazz) {
				rt = new Type.Clazz("java.lang","Object");
			} else if(lhs_t.equals(rhs_t)) {
				rt = lhs_t;
			} else {
				syntax_error("cannot determine result type for ternary operator",e);
				return; // dead code
			}
			
			e.attributes().add(rt);
		} else {
			// i'm not sure how you can get here.
			syntax_error("cannot determine result type for ternary operator",e);
		}
	}
	
	/**
	 * Determine whether or not the given type is a wrapper for a primitive
	 * type.  E.g. java.lang.Integer is a wrapper for int.
	 * 
	 * @param t
	 * @return
	 */
	protected static boolean isWrapper(Type t) {
		if(!(t instanceof Type.Clazz)) {
			return false;
		}
		Type.Clazz ref = (Type.Clazz) t;
		if(ref.pkg().equals("java.lang") && ref.components().size() == 1) {
			String s = ref.components().get(0).first();
			if(s.equals("Byte") || s.equals("Character") || s.equals("Short") ||
				s.equals("Integer") || s.equals("Long")
					|| s.equals("Float") || s.equals("Double")
					|| s.equals("Boolean")) {
				return true;
			}
		}
		return false;
	}
	/**
	 * Determine whether or not the given type is a wrapper for a primitive
	 * type. E.g. java.lang.Integer is a wrapper for int.
	 * 
	 * @param t
	 *            --- type to test
	 * @param wrapper
	 *            --- specific wrapper class to look for (i.e. Integer, Boolean,
	 *            Character).
	 * @return
	 */
	protected static boolean isWrapper(Type t, String wrapper) {
		if(!(t instanceof Type.Clazz)) {
			return false;
		}
		Type.Clazz ref = (Type.Clazz) t;
		if(ref.pkg().equals("java.lang") && ref.components().size() == 1) {
			String s = ref.components().get(0).first();
			if(s.equals(wrapper)) {
				return true;
			}
		}
		return false;
	}
		
	/**
     * Given the types of the left-hand and right-hand sides for a binary
     * operator, determine the appropriate type for that operator. This method
     * follows the Java Language Specification 5.6.1:
     * 
     * @param lhs
     * @param var
     * @return
     */
	public Type.Primitive unaryNumericPromotion(Type lhs, SyntacticElement e) {
		// First, we must unbox either operand if they are boxed.		
		if(lhs instanceof Type.Clazz) {
			lhs = unboxedType((Type.Clazz) lhs,e);
		}
		
		if (lhs instanceof Type.Char || lhs instanceof Type.Short
				|| lhs instanceof Type.Byte) {
			return new Type.Int();
		}
		
		return (Type.Primitive) lhs;
	}
	
	/**
     * Given the types of the left-hand and right-hand sides for a binary
     * operator, determine the appropriate type for that operator. This method
     * follows the Java Language Specification 5.6.2:
     * 
     * @param lhs
     * @param rhs
     * @return
     */
	public Type.Primitive binaryNumericPromotion(Type lhs, Type rhs, SyntacticElement e) {
		
		// First, we must unbox either operand if they are boxed.
		if(lhs instanceof Type.Clazz) {
			lhs = unboxedType((Type.Clazz) lhs,e);
		}
		if(rhs instanceof Type.Clazz) {
			rhs = unboxedType((Type.Clazz) rhs,e);
		}
		
		// Second, convert to the appropriate type
		if(lhs instanceof Type.Double || rhs instanceof Type.Double) {
			return new Type.Double();
		}
		if(lhs instanceof Type.Float || rhs instanceof Type.Float) {
			return new Type.Float();
		}
		if(lhs instanceof Type.Long || rhs instanceof Type.Long) {
			return new Type.Long();
		}
		
		// The following is not part of JLS 5.6.2, but is handy for dealing with
        // boolean operators &, |, ^ etc.
		if(lhs instanceof Type.Bool && rhs instanceof Type.Bool) {
			return new Type.Bool();
		}
		
		return new Type.Int();		
	}
	
	/**
     * Given a primitive type, determine the equivalent boxed type. For example,
     * the primitive type int yields the type java.lang.Integer. For simplicity
     * in the code using this, it returns in the form a java.Type, rather than a
     * jil.Type.
     * 
     * @param p
     * @return
     */
	public static Type.Reference boxedType(Type.Primitive p) {
		if(p instanceof Type.Bool) {
			return new Type.Clazz("java.lang","Boolean");
		} else if(p instanceof Type.Byte) {
			return new Type.Clazz("java.lang","Byte");
		} else if(p instanceof Type.Char) {
			return new Type.Clazz("java.lang","Character");
		} else if(p instanceof Type.Short) {
			return new Type.Clazz("java.lang","Short");
		} else if(p instanceof Type.Int) {
			return new Type.Clazz("java.lang","Integer");
		} else if(p instanceof Type.Long) {
			return new Type.Clazz("java.lang","Long");
		} else if(p instanceof Type.Float) {
			return new Type.Clazz("java.lang","Float");
		} else {
			return new Type.Clazz("java.lang","Double");
		}
	}
	
	/**
	 * Given a primitive wrapper class (i.e. a boxed type), return the unboxed
	 * equivalent. For example, java.lang.Integer yields int, whilst
	 * java.lang.Boolean yields bool.
	 * 
	 * @param p
	 * @return
	 */
	protected Type.Primitive unboxedType(Type.Clazz p, SyntacticElement e) {
		assert isWrapper(p);		
		String type = p.components().get(p.components().size()-1).first();
		
		if(type.equals("Boolean")) {
			return new Type.Bool();
		} else if(type.equals("Byte")) {
			return new Type.Byte();
		} else if(type.equals("Character")) {
			return new Type.Char();
		} else if(type.equals("Short")) {
			return new Type.Short();
		} else if(type.equals("Integer")) {
			return new Type.Int();
		} else if(type.equals("Long")) {
			return new Type.Long();
		} else if(type.equals("Float")) {
			return new Type.Float();
		} else if(type.equals("Double")) {
			return new Type.Double();
		} else {
			syntax_error("unknown boxed type \"" + p.toString()
					+ "\" encountered.",e);
			return null; // very dead!
		}
	}
	
	/**
	 * This method looks at the actual type of an expression (1st param), and
	 * compares it with the required type (2nd param). If they are different it
	 * inserts an implicit type conversion. This is useful, since it means we
	 * only have to work out these type conversions the once, rather than every
	 * time we encounter an expression.
	 * 
	 * @param e - the expression whose actual type is to be compared.
	 * @param t - the required type of the expression.
	 * @return
	 */
	protected Expr implicitCast(Expr e, Type t) {
		if(e == null) { return null; }
		Type e_t = (Type) e.attribute(Type.class);
		// insert implicit casts for primitive types.
		if (!e_t.equals(t)
				&& (t instanceof Type.Primitive && e_t instanceof Type.Primitive)) {			
			e = new Expr.Convert(fromJilType((Type.Primitive)t), e, t, e.attribute(SourceLocation.class));
		} else if(t instanceof Type.Primitive && e_t instanceof Type.Clazz) {
			Type.Clazz r = (Type.Clazz) e_t;
			if (r.pkg().equals("java.lang") && r.components().size() == 1) {
				String c = r.components().get(0).first();
				if (c.equals("Byte")) {
					Type.Function funType = new Type.Function(new Type.Byte());
					return implicitCast(new Expr.Invoke(e, "byteValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Byte(), funType), t);
				} else if (c.equals("Character")) {
					Type.Function funType = new Type.Function(new Type.Char());
					return implicitCast(new Expr.Invoke(e, "charValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Char(), funType), t);
				} else if (c.equals("Short")) {
					Type.Function funType = new Type.Function(new Type.Short());
					return implicitCast(new Expr.Invoke(e, "shortValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Short(), funType), t);
				} else if (c.equals("Integer")) {
					Type.Function funType = new Type.Function(new Type.Int());
					return implicitCast(new Expr.Invoke(e, "intValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Int(), funType), t);
				} else if (c.equals("Long")) {
					Type.Function funType = new Type.Function(new Type.Long());
					return implicitCast(new Expr.Invoke(e, "longValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Long(), funType), t);
				} else if (c.equals("Float")) {
					Type.Function funType = new Type.Function(new Type.Float());
					return implicitCast(new Expr.Invoke(e, "floatValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Float(), funType), t);
				} else if (c.equals("Double")) {
					Type.Function funType = new Type.Function(new Type.Double());
					return implicitCast(new Expr.Invoke(e, "doubleValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Double(), funType), t);
				} else if (c.equals("Boolean")) {
					Type.Function funType = new Type.Function(new Type.Bool());
					return implicitCast(new Expr.Invoke(e, "booleanValue",
							new ArrayList<Expr>(), new ArrayList(),
							new Type.Bool(), funType), t);
				} else {
					throw new RuntimeException("Unreachable code reached!");
				}
			}
		} else if(e_t instanceof Type.Primitive && t instanceof Type.Clazz) {
			ArrayList<Expr> params = new ArrayList<Expr>();
			params.add(e);
			Type.Function funType = new Type.Function(new Type.Void(),e_t);
			return new Expr.New(fromJilType(boxedType((Type.Primitive) e_t)),
					null, params, new ArrayList<Decl>(),
					boxedType((Type.Primitive) e_t), funType, e
							.attribute(SourceLocation.class));			
		} 
		
		return e;
	}
	
	/**
     * An unknown constant is a constant expression without any explicit type
     * labels. For example:
     * 
     * <pre>
     * short x = 1 + 1;
     * </pre>
     * 
     * Here, "1 + 1" is an unknown constant expression, since the type of it
     * must be inferred from the assignment. That is because, if the type of
     * "1+1" were resolved to be int, then the above could not compile!
     * 
     * @param e
     * @return
     */
	protected boolean isUnknownConstant(Expr e) {
		if(e instanceof Value.Int) {
			return true;
		} else if(e instanceof Expr.BinOp) {
			Expr.BinOp bop = (Expr.BinOp) e;
			
			switch(bop.op()) {
				case Expr.BinOp.ADD:
				case Expr.BinOp.SUB:
				case Expr.BinOp.MUL:
				case Expr.BinOp.DIV:
				case Expr.BinOp.MOD:
					return isUnknownConstant(bop.lhs()) && isUnknownConstant(bop.rhs());
					
				case Expr.BinOp.SHL:
				case Expr.BinOp.SHR:
				case Expr.BinOp.USHR:								
					return isUnknownConstant(bop.lhs()) && isUnknownConstant(bop.rhs()); 								
			}
		} else if(e instanceof Expr.UnOp) {
			Expr.UnOp uop = (Expr.UnOp) e;
			switch(uop.op()) {
				case Expr.UnOp.NEG:
				case Expr.UnOp.INV:
					return isUnknownConstant(uop.expr());
			}
		}
		
		return false;
	}
	
	/**
     * An unknown constant is a constant expression without any explicit type
     * labels. For example:
     * 
     * <pre>
     * short x = 1 + 1;
     * </pre>
     * 
     * Here, "1 + 1" is an unknown constant expression, since the type of it
     * must be inferred from the assignment. That is because, if the type of
     * "1+1" were resolved to be int, then the above could not compile!
     * 
     * @param e
     * @return
     */
	protected int evaluateUnknownConstant(Expr e) {
		if(e instanceof Value.Int) {
			return ((Value.Int)e).value();
		} else if(e instanceof Expr.BinOp) {
			Expr.BinOp bop = (Expr.BinOp) e;
			
			int lhs = evaluateUnknownConstant(bop.lhs());
			int rhs = evaluateUnknownConstant(bop.rhs());
			
			switch(bop.op()) {
				case Expr.BinOp.ADD:
					return lhs + rhs;					
				case Expr.BinOp.SUB:
					return lhs - rhs;					
				case Expr.BinOp.MUL:
					return lhs * rhs;					
				case Expr.BinOp.DIV:
					return lhs / rhs;					
				case Expr.BinOp.MOD:
					return lhs % rhs;					
				case Expr.BinOp.SHL:
					return lhs << rhs;
				case Expr.BinOp.SHR:
					return lhs >> rhs;
				case Expr.BinOp.USHR:
					return lhs >>> rhs;					 							
			}
		} else if(e instanceof Expr.UnOp) {
			Expr.UnOp uop = (Expr.UnOp) e;
			int lhs = evaluateUnknownConstant(uop.expr());
			
			switch(uop.op()) {
				case Expr.UnOp.NEG:
					return -lhs;
				case Expr.UnOp.INV:
					return ~lhs;
			}
		}
		
		syntax_error("cannot evaluate a known expression!",e);
		return 0; // unreachable
	}
	
	/**
     * This method accepts an unknown constant expression, and a required type
     * and creates the appropriate value object.
     * 
     * @param c
     * @param t
     */
	protected Expr unknownConstantInference(Expr e, Type lhs_t, SourceLocation loc) {
		int val = evaluateUnknownConstant(e);
		// first do primitive types
		if(lhs_t instanceof Type.Byte && val >= -128 && val <= 127) {
			return new Value.Byte((byte)val, new Type.Byte(), loc);				
		} else if(lhs_t instanceof Type.Char && val >= 0 && val <= 65535) {
			return new Value.Char((char)val, new Type.Char(), loc);				
		} else if(lhs_t instanceof Type.Short && val >= -32768 && val <= 32768) {
			return new Value.Short((short)val, new Type.Short(), loc);				
		} else if(isWrapper(lhs_t)) {
			Type.Clazz ref = (Type.Clazz) lhs_t;			
			String s = ref.components().get(0).first();				
			if(s.equals("Byte") && val >= -128 && val <= 127) {
				ArrayList<Expr> params = new ArrayList<Expr>();
				params.add(new Value.Byte((byte)val));
				Type.Function funType = new Type.Function(new Type.Void(),new Type.Byte());
				return new Expr.New(fromJilType(lhs_t),null,params,new ArrayList<Decl>(), lhs_t, funType, loc);				
			} else if(s.equals("Character") && val >= 0 && val <= 65535) {
				ArrayList<Expr> params = new ArrayList<Expr>();
				params.add(new Value.Char((char)val));
				Type.Function funType = new Type.Function(new Type.Void(),new Type.Char());
				return new Expr.New(fromJilType(lhs_t),null,params,new ArrayList<Decl>(), lhs_t, funType, loc);				
			} else if(s.equals("Short") && val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
				ArrayList<Expr> params = new ArrayList<Expr>();
				params.add(new Value.Short((short)val));
				Type.Function funType = new Type.Function(new Type.Void(),new Type.Short());
				return new Expr.New(fromJilType(lhs_t),null,params,new ArrayList<Decl>(), lhs_t, funType, loc);				
			} else if(s.equals("Integer") && val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
				ArrayList<Expr> params = new ArrayList<Expr>();
				params.add(new Value.Int(val));
				Type.Function funType = new Type.Function(new Type.Void(),new Type.Int());
				return new Expr.New(fromJilType(lhs_t),null,params,new ArrayList<Decl>(), lhs_t, funType, loc);				
			} 
		} 
		return new Value.Int(val,new Type.Int(),loc);
	}
	
	/**
     * Convert a type in jil to a type in java. This method is annoying, since
     * it seems to be converting to the same thing. However, there is a subtle
     * difference, in that a Java type represents a type as written in the
     * source code, rather than the abstract notion of a type.
     * 
     * @param jt
     * @return
     */
	protected jkit.java.tree.Type fromJilType(jkit.jil.tree.Type t) {		
		if(t instanceof jkit.jil.tree.Type.Primitive) {
			return fromJilType((jkit.jil.tree.Type.Primitive)t);
		} else if(t instanceof jkit.jil.tree.Type.Array) {
			return fromJilType((jkit.jil.tree.Type.Array)t);
		} else if(t instanceof jkit.jil.tree.Type.Clazz) {
			return fromJilType((jkit.jil.tree.Type.Clazz)t);
		}
		throw new RuntimeException("Need to finish fromJilType off!");
	}
	
	protected jkit.java.tree.Type.Primitive fromJilType(jkit.jil.tree.Type.Primitive pt) {
		if(pt instanceof jkit.jil.tree.Type.Void) {
			return new jkit.java.tree.Type.Void(pt);
		} else if(pt instanceof jkit.jil.tree.Type.Bool) {
			return new jkit.java.tree.Type.Bool(pt);
		} else if(pt instanceof jkit.jil.tree.Type.Byte) {
			return new jkit.java.tree.Type.Byte(pt);
		} else if(pt instanceof jkit.jil.tree.Type.Char) {
			return new jkit.java.tree.Type.Char(pt);
		} else if(pt instanceof jkit.jil.tree.Type.Short) {
			return new jkit.java.tree.Type.Short(pt);
		} else if(pt instanceof jkit.jil.tree.Type.Int) {
			return new jkit.java.tree.Type.Int(pt);
		} else if(pt instanceof jkit.jil.tree.Type.Long) {
			return new jkit.java.tree.Type.Long(pt);
		} else if(pt instanceof jkit.jil.tree.Type.Float) {
			return new jkit.java.tree.Type.Float(pt);
		} else {
			return new jkit.java.tree.Type.Double(pt);
		}
	}
	
	protected jkit.java.tree.Type.Array fromJilType(jkit.jil.tree.Type.Array at) {
		return new jkit.java.tree.Type.Array(fromJilType(at.element()),at);
	}
	
	protected jkit.java.tree.Type.Clazz fromJilType(jkit.jil.tree.Type.Clazz jt) {			
		// I will make it fully qualified for simplicity.
		ArrayList<Pair<String,List<jkit.java.tree.Type.Reference>>> ncomponents = new ArrayList();
		// So, we need to split out the package into the component parts
		String pkg = jt.pkg();
		int idx = 0;
		int start = 0;		
		while((idx = pkg.indexOf('.',start)) != -1) {
			ncomponents.add(new Pair(pkg.substring(start,idx),new ArrayList()));			
			start = idx+1;
		}		
		
		// Now, complete the components list
		for(Pair<String,List<jkit.jil.tree.Type.Reference>> c : jt.components()) {
			ArrayList<jkit.java.tree.Type.Reference> l = new ArrayList();
			for(jkit.jil.tree.Type.Reference r : c.second()) {
				l.add((jkit.java.tree.Type.Reference)fromJilType(r));
			}
			ncomponents.add(new Pair(c.first(),l));
		}
		
		return new jkit.java.tree.Type.Clazz(ncomponents,jt);
	}
	
	/**
     * Check wither a given type is a reference to java.lang.String or not.
     * 
     * @param t
     * @return
     */
	protected static boolean isString(Type t) {
		if(t instanceof Type.Clazz) {
			Type.Clazz c = (Type.Clazz) t;
			 return c.pkg().equals("java.lang") && c.components().size() == 1
					&& c.components().get(0).first().equals("String");			
		}
		return false;
	}
	
	/**
	 * This method simply determines the super class of the given class.
	 * 
	 * @param c
	 * @return
	 */
	protected Type.Clazz getSuperClass(Type.Clazz c) throws ClassNotFoundException {
		jkit.compiler.Clazz cc = loader.loadClass(c);
		return cc.superClass();
	}
	
	/**
     * This method is just to factor out the code for looking up the source
     * location and throwing an exception based on that.
     * 
     * @param msg --- the error message
     * @param e --- the syntactic element causing the error
     */
	protected void syntax_error(String msg, SyntacticElement e) {
		SourceLocation loc = (SourceLocation) e.attribute(SourceLocation.class);
		throw new SyntaxError(msg,loc.line(),loc.column());
	}
	
	/**
	 * This method is just to factor out the code for looking up the source
	 * location and throwing an exception based on that. In this case, we also
	 * have an internal exception which has given rise to this particular
	 * problem.
	 * 
	 * @param msg
	 *            --- the error message
	 * @param e
	 *            --- the syntactic element causing the error
	 * @parem ex --- an internal exception, the details of which we want to
	 *        keep.
	 */
	protected void syntax_error(String msg, SyntacticElement e, Throwable ex) {
		SourceLocation loc = (SourceLocation) e.attribute(SourceLocation.class);
		throw new SyntaxError(msg,loc.line(),loc.column(),ex);
	}
}

