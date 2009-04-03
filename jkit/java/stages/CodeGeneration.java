package jkit.java.stages;

import java.util.*;

import jkit.compiler.FieldNotFoundException;
import jkit.compiler.SyntaxError;
import jkit.compiler.ClassLoader;
import jkit.java.io.JavaFile;
import jkit.java.tree.Decl;
import jkit.java.*;
import jkit.jil.*;
import jkit.jil.tree.*;
import jkit.jil.tree.Type;
import jkit.util.Pair;
import jkit.util.Triple;

/**
 * <p>
 * The aim of this stage is to generate Jil code representing this class.
 * </p>
 * 
 * @author djp
 * 
 */
public class CodeGeneration {
	private ClassLoader loader = null;
	private TypeSystem types = null;
	
	private static class Scope {}
	private static class LoopScope extends Scope {
		public String continueLab;
		public String exitLab;
		public LoopScope(String cl, String el) {
			continueLab = cl;
			exitLab = el;
		}
	}
	
	private final Stack<Scope> scopes = new Stack<Scope>();
	
	public CodeGeneration(ClassLoader loader, TypeSystem types) {
		this.loader = loader;
		this.types = types;
	}
	
	public void apply(JavaFile file) {
		// Now, traverse the declarations
		for(Decl d : file.declarations()) {
			doDeclaration(d, null);
		}		
	}
	
	protected void doDeclaration(Decl d, JilClass parent) {
		if(d instanceof Decl.JavaInterface) {
			doInterface((Decl.JavaInterface)d);
		} else if(d instanceof Decl.JavaClass) {
			doClass((Decl.JavaClass)d);
		} else if(d instanceof Decl.JavaMethod) {
			doMethod((Decl.JavaMethod)d, parent);
		} else if(d instanceof Decl.JavaField) {
			doField((Decl.JavaField)d, parent);
		} else if (d instanceof Decl.InitialiserBlock) {
			doInitialiserBlock((Decl.InitialiserBlock) d);
		} else if (d instanceof Decl.StaticInitialiserBlock) {
			doStaticInitialiserBlock((Decl.StaticInitialiserBlock) d);
		} else {
			syntax_error("internal failure (unknown declaration \"" + d
					+ "\" encountered)",d);
		}
	}
	
	protected void doInterface(Decl.JavaInterface d) {
		doClass(d);
	}
	
	protected void doClass(Decl.JavaClass c) {
		Type.Clazz type = (Type.Clazz) c.attribute(Type.class);
		try {
			// We, need to update the skeleton so that any methods and fields
			// discovered below this are attributed to this class!			
			JilClass skeleton = (JilClass) loader.loadClass(type);
			
			// I do fields after everything else, so as to simplify the process
			// of adding field initialisers to constructors. This is because it
			// means I can be sure that the constructor has been otherwise
			// completely generated, so all I need is to add initialisers at
			// beginning, after super call (if there is one).
			ArrayList<Decl.JavaField> fields = new ArrayList<Decl.JavaField>();
			for(Decl d : c.declarations()) {
				if(!(d instanceof Decl.JavaField)) {
					doDeclaration(d, skeleton);
				} else {
					fields.add((Decl.JavaField)d);
				}
			}
			
			// Note, I iterate the field declarations in reverse order to ensure
			// that field initialisers are added to constructors in the right
			// order.
			for(int i=fields.size();i>0;--i) {
				Decl.JavaField d = fields.get(i-1);
				doDeclaration(d, skeleton);				
			}			
		} catch(ClassNotFoundException cne) {
			syntax_error("internal failure (skeleton not found for " + type,c,cne);
		}			
	}

	protected void doMethod(Decl.JavaMethod d, JilClass parent) {			
		Type.Function type = (Type.Function) d.attribute(Type.class);
		List<JilStmt> stmts = doStatement(d.body());
		// First, off. If this is a constructor, then check whether there is an
		// explicit super constructor call or not.  If not, then add one.
		if (d.name().equals(parent.name())) {
			if(!superCallFirst(stmts)) {			
				stmts.add(0, new JilExpr.Invoke(new JilExpr.Variable("super", parent
						.superClass()), "super", new ArrayList<JilExpr>(),
						new Type.Function(new Type.Void()), new Type.Void()));
			} 
		}				
		
		// Now, add this statement list to the jil method representing this java
		// method.
		
		for(JilMethod m : parent.methods()) {
			if(m.type().equals(type)) {
				m.body().addAll(stmts);
			}
		}			
	}

	protected void doField(Decl.JavaField d, JilClass parent) {		
		Pair<JilExpr,List<JilStmt>> tmp = doExpression(d.initialiser());
		Type fieldT = (Type) d.type().attribute(Type.class);
		boolean isStatic = d.isStatic();
		
		if(tmp != null) {
			// This field has an initialiser. Therefore, we need to add it to
			// the beginning of all constructors. One issue is that, if the
			// first statement of a constructor is a super call (which is should
			// normally be), then we need to put the statements after that.
			for(JilMethod m : parent.methods()) {
				if(m.name().equals(parent.name())) {
					List<JilStmt> body = m.body();
					JilExpr.Deref df = new JilExpr.Deref(new JilExpr.Variable("this",
							parent.type()), d.name(), isStatic, fieldT, d
							.attributes());
					JilStmt.Assign ae = new JilStmt.Assign(df, tmp.first(), d
							.attributes());
					if(superCallFirst(body)) {
						body.add(1,ae);
						body.addAll(1,tmp.second());
					} else {
						body.add(1,ae);
						body.addAll(0,tmp.second());
					}
				}
			}
		}				
	}
	
	protected void doInitialiserBlock(Decl.InitialiserBlock d) {
		for (jkit.java.tree.Stmt s : d.statements()) {
			doStatement(s);
		}	
	}
	
	protected void doStaticInitialiserBlock(Decl.StaticInitialiserBlock d) {		
		for (jkit.java.tree.Stmt s : d.statements()) {
			doStatement(s);
		}		
	}
	
	protected List<JilStmt> doStatement(jkit.java.tree.Stmt e) {
		if(e instanceof jkit.java.tree.Stmt.SynchronisedBlock) {
			return doSynchronisedBlock((jkit.java.tree.Stmt.SynchronisedBlock)e);
		} else if(e instanceof jkit.java.tree.Stmt.TryCatchBlock) {
			return doTryCatchBlock((jkit.java.tree.Stmt.TryCatchBlock)e);
		} else if(e instanceof jkit.java.tree.Stmt.Block) {
			return doBlock((jkit.java.tree.Stmt.Block)e);
		} else if(e instanceof jkit.java.tree.Stmt.VarDef) {
			return doVarDef((jkit.java.tree.Stmt.VarDef) e);
		} else if(e instanceof jkit.java.tree.Stmt.Assignment) {
			return doAssignment((jkit.java.tree.Stmt.Assignment) e).second();
		} else if(e instanceof jkit.java.tree.Stmt.Return) {
			return doReturn((jkit.java.tree.Stmt.Return) e);
		} else if(e instanceof jkit.java.tree.Stmt.Throw) {
			return doThrow((jkit.java.tree.Stmt.Throw) e);
		} else if(e instanceof jkit.java.tree.Stmt.Assert) {
			return doAssert((jkit.java.tree.Stmt.Assert) e);
		} else if(e instanceof jkit.java.tree.Stmt.Break) {
			return doBreak((jkit.java.tree.Stmt.Break) e);
		} else if(e instanceof jkit.java.tree.Stmt.Continue) {
			return doContinue((jkit.java.tree.Stmt.Continue) e);
		} else if(e instanceof jkit.java.tree.Stmt.Label) {
			return doLabel((jkit.java.tree.Stmt.Label) e);
		} else if(e instanceof jkit.java.tree.Stmt.If) {
			return doIf((jkit.java.tree.Stmt.If) e);
		} else if(e instanceof jkit.java.tree.Stmt.For) {
			return doFor((jkit.java.tree.Stmt.For) e);
		} else if(e instanceof jkit.java.tree.Stmt.ForEach) {
			return doForEach((jkit.java.tree.Stmt.ForEach) e);
		} else if(e instanceof jkit.java.tree.Stmt.While) {
			return doWhile((jkit.java.tree.Stmt.While) e);
		} else if(e instanceof jkit.java.tree.Stmt.DoWhile) {
			return doDoWhile((jkit.java.tree.Stmt.DoWhile) e);
		} else if(e instanceof jkit.java.tree.Stmt.Switch) {
			return doSwitch((jkit.java.tree.Stmt.Switch) e);
		} else if(e instanceof jkit.java.tree.Expr.Invoke) {
			Pair<JilExpr, List<JilStmt>> r = doInvoke((jkit.java.tree.Expr.Invoke) e);
			r.second().add((JilExpr.Invoke) r.first());
			return r.second();
		} else if(e instanceof jkit.java.tree.Expr.New) {
			return doNew((jkit.java.tree.Expr.New) e).second();
		} else if(e instanceof Decl.JavaClass) {
			doClass((Decl.JavaClass)e);			
		} else if(e != null) {
			syntax_error("Invalid statement encountered: "
					+ e.getClass(),e);
		}		
		return new ArrayList<JilStmt>();
	}
	
	protected List<JilStmt> doBlock(jkit.java.tree.Stmt.Block block) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		if(block != null) {		
			// now process every statement in this block.
			for(jkit.java.tree.Stmt s : block.statements()) {
				r.addAll(doStatement(s));
			}		
		}
		return r;
	}
	
	protected List<JilStmt> doCatchBlock(jkit.java.tree.Stmt.CatchBlock block) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		if(block != null) {			
			// now process every statement in this block.
			for(jkit.java.tree.Stmt s : block.statements()) {
				r.addAll(doStatement(s));
			}		
		}
		return r;
	}
	
	protected List<JilStmt> doSynchronisedBlock(jkit.java.tree.Stmt.SynchronisedBlock block) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		r.addAll(doBlock(block));
		doExpression(block.expr());
		// need to add synch enter and leave here ?
		return r;
	}
	
	protected List<JilStmt> doTryCatchBlock(jkit.java.tree.Stmt.TryCatchBlock block) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		r.addAll(doBlock(block));		
		r.addAll(doBlock(block.finaly()));		
		
		// OK, MAJOR BUGS HERE ...
		
		for(jkit.java.tree.Stmt.CatchBlock cb : block.handlers()) {
			r.addAll(doCatchBlock(cb));
		}
		return r;
	}
	
	protected List<JilStmt> doVarDef(jkit.java.tree.Stmt.VarDef def) {		
		Type type = (Type) def.type().attribute(Type.class);
		List<Triple<String, Integer, jkit.java.tree.Expr>> defs = def.definitions();
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		for(int i=0;i!=defs.size();++i) {
			Triple<String, Integer, jkit.java.tree.Expr> d = defs.get(i);
			Type nt = type;
													
			for(int j=0;j!=d.second();++j) {
				nt = new Type.Array(nt);
			}

			if(d.third() != null) {
				Pair<JilExpr,List<JilStmt>> e = doExpression(d.third());
				r.addAll(e.second());
				JilExpr lhs = new JilExpr.Variable(d.first(), nt, def
						.attributes());
				r.add(new JilStmt.Assign(lhs, e.first()));
			}
		}
		
		return r;
	}
	
	protected Pair<JilExpr,List<JilStmt>> doAssignment(jkit.java.tree.Stmt.Assignment def) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		Pair<JilExpr,List<JilStmt>> lhs = doExpression(def.lhs());	
		Pair<JilExpr,List<JilStmt>> rhs = doExpression(def.rhs());
		r.addAll(lhs.second());
		r.addAll(rhs.second());
		r.add(new JilStmt.Assign(lhs.first(),rhs.first(),def.attributes()));
		return new Pair(rhs.first(),r);
	}
	
	protected List<JilStmt> doReturn(jkit.java.tree.Stmt.Return ret) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		if(ret.expr() != null) {
			Pair<JilExpr,List<JilStmt>> expr = doExpression(ret.expr());
			r.addAll(expr.second());
			r.add(new JilStmt.Return(expr.first(),ret.attributes()));
		} else {
			r.add(new JilStmt.Return(null,ret.attributes()));
		}
		return r;
	}
	
	protected List<JilStmt> doThrow(jkit.java.tree.Stmt.Throw ret) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		Pair<JilExpr,List<JilStmt>> expr = doExpression(ret.expr());
		r.addAll(expr.second());
		r.add(new JilStmt.Throw(expr.first(),ret.attributes()));
		return r;
	}
	
	protected List<JilStmt> doAssert(jkit.java.tree.Stmt.Assert ret) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		Pair<JilExpr,List<JilStmt>> expr = doExpression(ret.expr());
		
		// need to do some real code generation here.
		
		return r;
	}
	
	protected List<JilStmt> doBreak(jkit.java.tree.Stmt.Break brk) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		
		if(brk.label() == null) {
			LoopScope ls = (LoopScope) findEnclosingScope(LoopScope.class);
			r.add(new JilStmt.Goto(ls.exitLab,brk.attributes()));
		} else {
			r.add(new JilStmt.Goto(brk.label(),brk.attributes()));
		}
		
		return r;
	}
	
	protected List<JilStmt> doContinue(jkit.java.tree.Stmt.Continue brk) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		
		if(brk.label() == null) {
			LoopScope ls = (LoopScope) findEnclosingScope(LoopScope.class);
			r.add(new JilStmt.Goto(ls.continueLab,brk.attributes()));
		} else {
			// this must be broken.
			r.add(new JilStmt.Goto(brk.label(),brk.attributes()));
		}
		
		return r;
	}
	
	protected List<JilStmt> doLabel(jkit.java.tree.Stmt.Label lab) {						
		List<JilStmt> r = doStatement(lab.statement());
		r.add(0, new JilStmt.Label(lab.label(), lab.attributes()));
		return r;
	}
	
	static protected int ifexit_label = 0;
	static protected int iftrue_label = 0;
	
	protected List<JilStmt> doIf(jkit.java.tree.Stmt.If stmt) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		
		Pair<JilExpr,List<JilStmt>> cond = doExpression(stmt.condition());
		List<JilStmt> tbranch = doStatement(stmt.trueStatement());
		List<JilStmt> fbranch = doStatement(stmt.falseStatement());
		
		r.addAll(cond.second());
		
		if(stmt.falseStatement() == null) {
			r.add(new JilStmt.IfGoto(
					new JilExpr.UnOp(cond.first(), JilExpr.UnOp.NOT, new Type.Bool(),
					stmt.condition().attributes()), "ifexit" + ifexit_label, stmt.attributes()));
			r.addAll(tbranch);
		} else if(stmt.trueStatement() == null) {
			r.add(new JilStmt.IfGoto(cond.first(),"ifexit" + ifexit_label,stmt.attributes()));
			r.addAll(fbranch);
		} else {
			r.add(new JilStmt.IfGoto(cond.first(),"iftrue" + iftrue_label,stmt.attributes()));
			r.addAll(fbranch);
			r.add(new JilStmt.Goto("ifexit" + ifexit_label,stmt.attributes()));
			r.add(new JilStmt.Label("iftrue" + iftrue_label++,stmt.attributes()));
			r.addAll(tbranch);
		}
		
		r.add(new JilStmt.Label("ifexit" + ifexit_label++,stmt.attributes()));
		return r;
		
	}
	
	static protected int whileheader_label = 0;
	static protected int whileexit_label = 0;
	
	protected List<JilStmt> doWhile(jkit.java.tree.Stmt.While stmt) {
		String headerLab = "whileheader" + whileheader_label++;
		String exitLab = "whileexit" + whileexit_label++;
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		
		r.add(new JilStmt.Label(headerLab, stmt
				.attributes()));
		Pair<JilExpr, List<JilStmt>> cond = doExpression(stmt.condition());
		r.addAll(cond.second());
		r.add(new JilStmt.IfGoto(new JilExpr.UnOp(cond.first(), JilExpr.UnOp.NOT,
				new Type.Bool(), stmt.condition().attributes()), exitLab, stmt
				.attributes()));
		scopes.push(new LoopScope(headerLab,exitLab));
		r.addAll(doStatement(stmt.body()));
		scopes.pop();
		r.add(new JilStmt.Goto(headerLab, stmt
				.attributes()));
		r.add(new JilStmt.Label(exitLab, stmt
						.attributes()));
		
		return r;
	}
	
	static protected int dowhileheader_label = 0;	
	static protected int dowhileexit_label = 0;
	
	protected List<JilStmt> doDoWhile(jkit.java.tree.Stmt.DoWhile stmt) {
		String headerLab = "dowhileheader" + dowhileheader_label++;
		String exitLab = "dowhileexit" + dowhileexit_label++;
		
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		
		r.add(new JilStmt.Label(headerLab, stmt
				.attributes()));
		scopes.push(new LoopScope(headerLab,exitLab));
		r.addAll(doStatement(stmt.body()));
		scopes.pop();
		Pair<JilExpr, List<JilStmt>> cond = doExpression(stmt.condition());
		r.addAll(cond.second());
		r.add(new JilStmt.IfGoto(cond.first(), headerLab, stmt.attributes()));
						
		return r;		
	}
	
	static protected int forheader_label = 0;
	static protected int forexit_label = 0;
	
	protected List<JilStmt> doFor(jkit.java.tree.Stmt.For stmt) {
		String headerLab = "forheader" + forheader_label++;
		String exitLab = "forexit" + forexit_label++;
		
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		
		if(stmt.initialiser() != null) {
			r.addAll(doStatement(stmt.initialiser()));
		}
		
		r.add(new JilStmt.Label(headerLab, stmt
				.attributes()));
		
		if(stmt.condition() != null) {
			Pair<JilExpr, List<JilStmt>> cond = doExpression(stmt.condition());
			r.addAll(cond.second());
			r.add(new JilStmt.IfGoto(new JilExpr.UnOp(cond.first(), JilExpr.UnOp.NOT,
					new Type.Bool(), stmt.condition().attributes()), exitLab,
					stmt.attributes()));
		}
		
		scopes.push(new LoopScope(headerLab,exitLab));
		r.addAll(doStatement(stmt.body()));
		scopes.pop();
		
		if(stmt.increment() != null) {
			r.addAll(doStatement(stmt.increment()));
		}
		
		r.add(new JilStmt.Goto(headerLab, stmt
				.attributes()));
		r.add(new JilStmt.Label(exitLab, stmt
				.attributes()));
		
		return r;
	}
	
	static protected int forallheader_label = 0;
	static protected int forallexit_label = 0;
	static protected int foralliter_label = 0;
	
	protected List<JilStmt> doForEach(jkit.java.tree.Stmt.ForEach stmt) {
		String headerLab = "forallheader" + forallheader_label++;
		String exitLab = "forallexit" + forallexit_label++;
		String iterLab = "foralliter" + foralliter_label++;
		
		ArrayList<JilStmt> stmts = new ArrayList<JilStmt>();
		
		Pair<JilExpr,List<JilStmt>> src = doExpression(stmt.source());
		JilExpr.Variable loopVar = new JilExpr.Variable(stmt.var(), (Type) stmt
				.type().attribute(Type.class), stmt.attributes());
		
		Type srcType = src.first().type();				
		
		stmts.addAll(src.second());
		JilExpr.Variable iter;
		
		if (srcType instanceof Type.Array) {
			iter = new JilExpr.Variable(iterLab, new Type.Int());
			stmts
					.add(new JilStmt.Assign(iter, new JilExpr.Int(0), stmt
							.attributes()));
		} else {
			// the following needs to be expanded upon, so as to include generic
			// information on the iterator. The easiest way to do this is to
			// look up the iterator() method in the src class, and use it's
			// return type.
			iter = new JilExpr.Variable(iterLab, new Type.Clazz("java.util",
					"Iterator"));			 

			stmts
					.add(new JilStmt.Assign(iter, new JilExpr.Invoke(src.first(),
							"iterator", new ArrayList<JilExpr>(),
							new Type.Function(new Type.Clazz("java.util",
									"Iterator")), new Type.Clazz("java.util",
									"Iterator")), stmt.attributes()));
		}				
		
		stmts.add(new JilStmt.Label(headerLab, stmt
				.attributes()));
		
		// Second, do condition
		
		if (srcType instanceof Type.Array) {
			JilExpr arrlength = new JilExpr.Deref(src.first(),"length",false,new Type.Int(), stmt
					.attributes());
			JilExpr gecmp = new JilExpr.BinOp(iter,arrlength,JilExpr.BinOp.GTEQ,new Type.Bool(), stmt
					.attributes());
			stmts.add(new JilStmt.IfGoto(gecmp,exitLab, stmt
					.attributes()));
			
			stmts.add(new JilStmt.Assign(loopVar, new JilExpr.ArrayIndex(src.first(),
					iter, loopVar.type())));
		} else {
			JilExpr hasnext = new JilExpr.Invoke(iter, "hasNext",
					new ArrayList<JilExpr>(), new Type.Function(new Type.Bool()),
					new Type.Bool(), stmt.attributes());
			stmts.add(new JilStmt.IfGoto(new JilExpr.UnOp(hasnext, JilExpr.UnOp.NOT,
					new Type.Bool()), exitLab));

			JilExpr next = new JilExpr.Invoke(iter, "next", new ArrayList<JilExpr>(),
					new Type.Function(new Type.Clazz("java.lang", "Object")),
					loopVar.type(), stmt.attributes());
			JilExpr cast = new JilExpr.Cast(next, loopVar.type());
			stmts.add(new JilStmt.Assign(loopVar, cast, stmt.attributes()));			
		}
		
		// Third, do body
		
		scopes.push(new LoopScope(headerLab,exitLab));
		stmts.addAll(doStatement(stmt.body()));	
		scopes.pop();
		
		// Fourth, do increment
		if (srcType instanceof Type.Array) {
			JilExpr.BinOp rhs = new JilExpr.BinOp(iter, new JilExpr.Int(1),
					JilExpr.BinOp.ADD, new Type.Int(), stmt.attributes());
			stmts.add(new JilStmt.Assign(iter,rhs,stmt.attributes()));
		} 
		
		stmts.add(new JilStmt.Goto(headerLab,stmt.attributes()));
		
		stmts.add(new JilStmt.Label(exitLab, stmt
				.attributes()));
		
		return stmts;
	}
	
	protected List<JilStmt> doSwitch(jkit.java.tree.Stmt.Switch sw) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		
		Pair<JilExpr,List<JilStmt>> cond = doExpression(sw.condition());
		for(jkit.java.tree.Stmt.Case c : sw.cases()) {
			doExpression(c.condition());
			for(jkit.java.tree.Stmt s : c.statements()) {
				doStatement(s);
			}
		}
		
		return r;
	}
	
	protected Pair<JilExpr,List<JilStmt>> doExpression(jkit.java.tree.Expr e) {	
		if(e instanceof jkit.java.tree.Value.Bool) {
			return doBoolVal((jkit.java.tree.Value.Bool)e);
		} else if(e instanceof jkit.java.tree.Value.Byte) {
			return doByteVal((jkit.java.tree.Value.Byte)e);
		} else if(e instanceof jkit.java.tree.Value.Char) {
			return doCharVal((jkit.java.tree.Value.Char)e);
		} else if(e instanceof jkit.java.tree.Value.Short) {
			return doShortVal((jkit.java.tree.Value.Short)e);
		} else if(e instanceof jkit.java.tree.Value.Int) {
			return doIntVal((jkit.java.tree.Value.Int)e);
		} else if(e instanceof jkit.java.tree.Value.Long) {
			return doLongVal((jkit.java.tree.Value.Long)e);
		} else if(e instanceof jkit.java.tree.Value.Float) {
			return doFloatVal((jkit.java.tree.Value.Float)e);
		} else if(e instanceof jkit.java.tree.Value.Double) {
			return doDoubleVal((jkit.java.tree.Value.Double)e);
		} else if(e instanceof jkit.java.tree.Value.String) {
			return doStringVal((jkit.java.tree.Value.String)e);
		} else if(e instanceof jkit.java.tree.Value.Null) {
			return doNullVal((jkit.java.tree.Value.Null)e);
		} else if(e instanceof jkit.java.tree.Value.TypedArray) {
			return doTypedArrayVal((jkit.java.tree.Value.TypedArray)e);
		} else if(e instanceof jkit.java.tree.Value.Array) {
			return doArrayVal((jkit.java.tree.Value.Array)e);
		} else if(e instanceof jkit.java.tree.Value.Class) {
			return doClassVal((jkit.java.tree.Value.Class) e);
		} else if(e instanceof jkit.java.tree.Expr.LocalVariable) {
			return doLocalVariable((jkit.java.tree.Expr.LocalVariable)e);
		} else if(e instanceof jkit.java.tree.Expr.NonLocalVariable) {
			return doNonLocalVariable((jkit.java.tree.Expr.NonLocalVariable)e);
		} else if(e instanceof jkit.java.tree.Expr.ClassVariable) {
			return doClassVariable((jkit.java.tree.Expr.ClassVariable)e);
		} else if(e instanceof jkit.java.tree.Expr.UnOp) {
			return doUnOp((jkit.java.tree.Expr.UnOp)e);
		} else if(e instanceof jkit.java.tree.Expr.BinOp) {
			return doBinOp((jkit.java.tree.Expr.BinOp)e);
		} else if(e instanceof jkit.java.tree.Expr.TernOp) {
			return doTernOp((jkit.java.tree.Expr.TernOp)e);
		} else if(e instanceof jkit.java.tree.Expr.Cast) {
			return doCast((jkit.java.tree.Expr.Cast)e);
		} else if(e instanceof jkit.java.tree.Expr.Convert) {
			return doConvert((jkit.java.tree.Expr.Convert)e);
		} else if(e instanceof jkit.java.tree.Expr.InstanceOf) {
			return doInstanceOf((jkit.java.tree.Expr.InstanceOf)e);
		} else if(e instanceof jkit.java.tree.Expr.Invoke) {
			return doInvoke((jkit.java.tree.Expr.Invoke) e);
		} else if(e instanceof jkit.java.tree.Expr.New) {
			return doNew((jkit.java.tree.Expr.New) e);
		} else if(e instanceof jkit.java.tree.Expr.ArrayIndex) {
			return doArrayIndex((jkit.java.tree.Expr.ArrayIndex) e);
		} else if(e instanceof jkit.java.tree.Expr.Deref) {
			return doDeref((jkit.java.tree.Expr.Deref) e);
		} else if(e instanceof jkit.java.tree.Stmt.Assignment) {
			// force brackets			
			return doAssignment((jkit.java.tree.Stmt.Assignment) e);			
		} else if(e != null) {
			syntax_error("Invalid expression encountered: "
					+ e.getClass(),e);			
		}
		
		return null;
	}
	
	protected Pair<JilExpr,List<JilStmt>> doDeref(jkit.java.tree.Expr.Deref e) {
		Pair<JilExpr,List<JilStmt>> target = doExpression(e.target());
		Type type = (Type) e.attribute(Type.class);
		Type.Reference _targetT = (Type.Reference) e.target().attribute(Type.class);
		
		if(_targetT instanceof Type.Clazz) {
			Type.Clazz targetT = (Type.Clazz) _targetT;
			try {
				Triple<jkit.compiler.Clazz, jkit.compiler.Clazz.Field, Type> r = types
				.resolveField(targetT, e.name(), loader);

				return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Deref(target.first(), e
						.name(),r.second().isStatic(), type,  e.attributes()),
						target.second());
			} catch(ClassNotFoundException cne) {
				syntax_error(cne.getMessage(),e,cne);				
			} catch(FieldNotFoundException fne) {	
				// this must be an error...
				syntax_error("field does not exist: " + type + "." + e.name(),e,fne);		
			}
		} else if(_targetT instanceof Type.Array && e.name().equals("length")) {
			return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Deref(target.first(), e
					.name(), false, type,  e.attributes()),
					target.second());
		} else {
			syntax_error("cannot dereference type " + _targetT,e);
		}
		return null; // dead code		
	}
	
	protected Pair<JilExpr,List<JilStmt>> doArrayIndex(jkit.java.tree.Expr.ArrayIndex e) {
		Pair<JilExpr,List<JilStmt>> target = doExpression(e.target());
		Pair<JilExpr,List<JilStmt>> index = doExpression(e.index());
		Type type = (Type) e.attribute(Type.class);
		
		List<JilStmt> r = target.second();
		r.addAll(index.second());
		
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.ArrayIndex(target.first(),
				index.first(), type, e.attributes()), r);
	}
	
	protected Pair<JilExpr,List<JilStmt>> doNew(jkit.java.tree.Expr.New e) {
		// Second, recurse through any parameters supplied ...
		ArrayList<JilStmt> r = new ArrayList();
		ArrayList<JilExpr> nparameters = new ArrayList();			
		Type.Reference type = (Type.Reference) e.attribute(Type.class);
		List<jkit.java.tree.Expr> parameters = e.parameters();
		
		for(int i=0;i!=parameters.size();++i) {
			jkit.java.tree.Expr p = parameters.get(i);
			Pair<JilExpr,List<JilStmt>> tmp = doExpression(p);
			nparameters.add(tmp.first());
			r.addAll(tmp.second());
		}
		
		if(e.declarations().size() > 0) {
			for(Decl d : e.declarations()) {
				doDeclaration(d, null); // bug here
			}			
		}
		
		Type.Function funType = (Type.Function) e
				.attribute(Type.Function.class);
		
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.New(type, nparameters,
				funType, e.attributes()), r);
	}
	
	protected Pair<JilExpr,List<JilStmt>> doInvoke(jkit.java.tree.Expr.Invoke e) {
		ArrayList<JilStmt> r = new ArrayList();
		ArrayList<JilExpr> nparameters = new ArrayList();
		Type type = (Type) e.attribute(Type.class);				
		Type.Function funType = (Type.Function) e.attribute(Type.Function.class);
		
		Pair<JilExpr,List<JilStmt>> target = doExpression(e.target());
		r.addAll(target.second());
		
		List<jkit.java.tree.Expr> parameters = e.parameters();
		for(int i=0;i!=parameters.size();++i) {
			jkit.java.tree.Expr p = parameters.get(i);
			Pair<JilExpr,List<JilStmt>> tmp = doExpression(p);
			nparameters.add(tmp.first());
			r.addAll(tmp.second());
		}				
		
		JilExpr rec = target.first();
		
		if (rec instanceof JilExpr.ClassVariable) {
			return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Invoke(target.first(), e
					.name(), nparameters, funType, type, e
					.attributes()), r);
		} else if (rec instanceof JilExpr.Variable
				&& ((JilExpr.Variable) rec).value().equals("super")) {
			return new Pair<JilExpr, List<JilStmt>>(new JilExpr.SpecialInvoke(target
					.first(), e.name(), nparameters, funType, type, e
					.attributes()), r);
		} else {
			return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Invoke(target.first(), e
					.name(), nparameters, funType, type, e
					.attributes()), r);
		}
	}
	
	protected Pair<JilExpr,List<JilStmt>> doInstanceOf(jkit.java.tree.Expr.InstanceOf e) {
		Pair<JilExpr,List<JilStmt>> lhs = doExpression(e.lhs());
		Type type = (Type) e.attribute(Type.class);
		Type rhs = (Type) e.rhs().attribute(Type.class);
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.InstanceOf(lhs.first(), rhs,
				type, e.attributes()), lhs.second());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doCast(jkit.java.tree.Expr.Cast e) {
		Pair<JilExpr,List<JilStmt>> expr = doExpression(e.expr());		
		Type type = (Type) e.attribute(Type.class);
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Cast(expr.first(),
				type, e.attributes()), expr.second());		
	}
	
	protected Pair<JilExpr,List<JilStmt>> doConvert(jkit.java.tree.Expr.Convert e) {
		Pair<JilExpr,List<JilStmt>> expr = doExpression(e.expr());		
		Type.Primitive type = (Type.Primitive) e.attribute(Type.class);
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Convert(type, expr.first(),
				e.attributes()), expr.second());		
	}
	
	protected Pair<JilExpr,List<JilStmt>> doBoolVal(jkit.java.tree.Value.Bool e) {
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Bool(e.value()),
				new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doCharVal(jkit.java.tree.Value.Char e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Char(e.value()), new ArrayList<JilStmt>());		
	}
	
	protected Pair<JilExpr,List<JilStmt>> doByteVal(jkit.java.tree.Value.Byte e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Byte(e.value()), new ArrayList<JilStmt>());		
	}
	
	protected Pair<JilExpr,List<JilStmt>> doShortVal(jkit.java.tree.Value.Short e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Short(e.value()), new ArrayList<JilStmt>());		
	}
	
	protected Pair<JilExpr,List<JilStmt>> doIntVal(jkit.java.tree.Value.Int e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Int(e.value()), new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doLongVal(jkit.java.tree.Value.Long e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Long(e.value()), new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doFloatVal(jkit.java.tree.Value.Float e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Float(e.value()), new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doDoubleVal(jkit.java.tree.Value.Double e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Double(e.value()), new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doStringVal(jkit.java.tree.Value.String e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.StringVal(e.value()), new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doNullVal(jkit.java.tree.Value.Null e) {
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Null(), new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doTypedArrayVal(jkit.java.tree.Value.TypedArray e) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		ArrayList<JilExpr> params = new ArrayList();
		Type.Array type = (Type.Array) e.attribute(Type.class);
		
		for(int i=0;i!=e.values().size();++i) {
			jkit.java.tree.Expr v = e.values().get(i);
			Pair<JilExpr,List<JilStmt>> p = doExpression(v);
			params.add(p.first());
			r.addAll(p.second());
		}
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Array(params,type,e.attributes()),r);
	}
	
	protected Pair<JilExpr,List<JilStmt>> doArrayVal(jkit.java.tree.Value.Array e) {
		ArrayList<JilStmt> r = new ArrayList<JilStmt>();
		ArrayList<JilExpr> params = new ArrayList();
		Type.Array type = (Type.Array) e.attribute(Type.class);

		for(int i=0;i!=e.values().size();++i) {
			jkit.java.tree.Expr v = e.values().get(i);			
			Pair<JilExpr,List<JilStmt>> p = doExpression(v);
			params.add(p.first());
			r.addAll(p.second());
		}
		
		return new Pair<JilExpr,List<JilStmt>>(new JilExpr.Array(params,type,e.attributes()),r);
	}
	
	protected Pair<JilExpr,List<JilStmt>> doClassVal(jkit.java.tree.Value.Class e) {
		Type.Clazz type = (Type.Clazz) e.attribute(Type.class);		
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Class(type, e.attributes()),
				new ArrayList<JilStmt>());
	}
		
	protected Pair<JilExpr, List<JilStmt>> doLocalVariable(
			jkit.java.tree.Expr.LocalVariable e) {
		Type type = (Type) e.attribute(Type.class);
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.Variable(e.value(), type, e
				.attributes()), new ArrayList<JilStmt>());
	}

	protected Pair<JilExpr, List<JilStmt>> doNonLocalVariable(
			jkit.java.tree.Expr.NonLocalVariable e) {
		syntax_error(
				"internal failure (support for non-local variables not implemented!)",
				e);
		return null;
	}
	
	protected Pair<JilExpr,List<JilStmt>> doClassVariable(jkit.java.tree.Expr.ClassVariable e) {
		Type.Clazz type = (Type.Clazz) e.attribute(Type.class);
		return new Pair<JilExpr, List<JilStmt>>(new JilExpr.ClassVariable(type, e.attributes()),
				new ArrayList<JilStmt>());
	}
	
	protected Pair<JilExpr,List<JilStmt>> doUnOp(jkit.java.tree.Expr.UnOp e) {		
		Pair<JilExpr, List<JilStmt>> r = doExpression(e.expr());		
		Type.Primitive type = (Type.Primitive) e.attribute(Type.class);
		List<JilStmt> stmts = r.second();
		jkit.java.tree.Expr.LocalVariable lval;

		switch (e.op()) {
		case jkit.java.tree.Expr.UnOp.PREDEC:
		{
			lval = (jkit.java.tree.Expr.LocalVariable) e.expr();			
			JilExpr.Variable lhs = new JilExpr.Variable(lval.value(),type,lval.attributes());
			JilExpr rhs = new JilExpr.BinOp(lhs, new JilExpr.Int(1), JilExpr.BinOp.SUB,
					type, e.attributes());
			stmts.add(new JilStmt.Assign(lhs,rhs,e.attributes()));
			return new Pair<JilExpr, List<JilStmt>>(r.first(),stmts);		
		}
		case jkit.java.tree.Expr.UnOp.PREINC:
		{
			lval = (jkit.java.tree.Expr.LocalVariable) e.expr();
			JilExpr.Variable lhs = new JilExpr.Variable(lval.value(),type,lval.attributes());
			JilExpr rhs = new JilExpr.BinOp(lhs, new JilExpr.Int(1), JilExpr.BinOp.ADD,
					type, e.attributes());
			stmts.add(new JilStmt.Assign(lhs,rhs,e.attributes()));
			return new Pair<JilExpr, List<JilStmt>>(r.first(),stmts);
		}
		case jkit.java.tree.Expr.UnOp.POSTINC:
		{
			lval = (jkit.java.tree.Expr.LocalVariable) e.expr();
			JilExpr.Variable tmp = new JilExpr.Variable("$tmp",type,lval.attributes());
			JilExpr.Variable lhs = new JilExpr.Variable(lval.value(),type,lval.attributes());
			stmts.add(new JilStmt.Assign(tmp,lhs,e.attributes()));			
			JilExpr rhs = new JilExpr.BinOp(lhs, new JilExpr.Int(1), JilExpr.BinOp.ADD,
					type, e.attributes());
			stmts.add(new JilStmt.Assign(lhs,rhs,e.attributes()));
			return new Pair<JilExpr, List<JilStmt>>(tmp,stmts);		
		}
		case jkit.java.tree.Expr.UnOp.POSTDEC:
		{
			 lval = (jkit.java.tree.Expr.LocalVariable) e.expr();
			 JilExpr.Variable tmp = new JilExpr.Variable("$tmp",type,lval.attributes());
				JilExpr.Variable lhs = new JilExpr.Variable(lval.value(),type,lval.attributes());
				stmts.add(new JilStmt.Assign(tmp,lhs,e.attributes()));			
				JilExpr rhs = new JilExpr.BinOp(lhs, new JilExpr.Int(1), JilExpr.BinOp.SUB,
						type, e.attributes());
				stmts.add(new JilStmt.Assign(lhs,rhs,e.attributes()));
				return new Pair<JilExpr, List<JilStmt>>(tmp,stmts);
		}
		default:
			return new Pair<JilExpr, List<JilStmt>>(new JilExpr.UnOp(r.first(), e.op(),
					type, e.attributes()), r.second());
		}		
	}
		
	protected Pair<JilExpr,List<JilStmt>> doBinOp(jkit.java.tree.Expr.BinOp e) {				
		Type _type = (Type) e.attribute(Type.class);
		
		if(_type instanceof Type.Primitive) {
			Pair<JilExpr,List<JilStmt>> lhs = doExpression(e.lhs());
			Pair<JilExpr,List<JilStmt>> rhs = doExpression(e.rhs());
			
			Type.Primitive type = (Type.Primitive) _type;

			List<JilStmt> r = lhs.second();
			r.addAll(rhs.second());

			return new Pair<JilExpr, List<JilStmt>>(new JilExpr.BinOp(lhs.first(), rhs
					.first(), e.op(), type, e.attributes()), r);
		} else if(e.op() == jkit.java.tree.Expr.BinOp.CONCAT) {
			return doStringConcat(e);
		} else {
			syntax_error("internal failure --- problem processing binary operator",e);
			return null;
		}
	}
	
	protected static int stringbuilder_label = 0;
	
	protected Pair<JilExpr,List<JilStmt>> doStringConcat(jkit.java.tree.Expr.BinOp bop){
		
		// This method is evidence as to why Java sucks as a programming
		// language. It should be easy to construct datatypes, as I'm doing
		// here, but lack of good notation makes it awkward in Java. Sure, there
		// are some hacks to can do to improve the situation but basically it's
		// screwed.
		String builderLab = "$builder" + stringbuilder_label++;
		Pair<JilExpr,List<JilStmt>> lhs = doExpression(bop.lhs());
		Pair<JilExpr,List<JilStmt>> rhs = doExpression(bop.rhs());
		
		List<JilStmt> stmts = lhs.second();
		stmts.addAll(lhs.second());
		stmts.addAll(rhs.second());
		
		Type.Clazz builder = new Type.Clazz("java.lang",
				"StringBuilder");
						
		stmts.add(new JilStmt.Assign(new JilExpr.Variable(builderLab, builder),
				new JilExpr.New(builder, new ArrayList<JilExpr>(),
						new Type.Function(new Type.Void()), bop.attributes())));					
		
		Type lhs_t = lhs.first().type(); 
		if(lhs_t instanceof Type.Primitive || isString(lhs_t)) {
			ArrayList<JilExpr> params = new ArrayList<JilExpr>();
			params.add(lhs.first());
			stmts.add(new JilExpr.Invoke(new JilExpr.Variable(builderLab, builder), "append",
					params, new Type.Function(new Type.Clazz("java.lang",
					"StringBuilder"), lhs.first().type()), new Type.Clazz(
							"java.lang", "StringBuilder")));
		} else {
			ArrayList<JilExpr> params = new ArrayList<JilExpr>();
			params.add(lhs.first());
			stmts.add(new JilExpr.Invoke(new JilExpr.Variable(builderLab, builder),
					"append", params, new Type.Function(new Type.Clazz(
							"java.lang", "StringBuilder"), new Type.Clazz(
							"java.lang", "Object")), new Type.Clazz(
							"java.lang", "StringBuilder")));	
		}

		// Now, do the right hand side
		JilExpr r;
		Type rhs_t = rhs.first().type(); 
		if(rhs_t instanceof Type.Primitive || isString(rhs_t)) {
			ArrayList<JilExpr> params = new ArrayList<JilExpr>();
			params.add(rhs.first());
			r = new JilExpr.Invoke(new JilExpr.Variable(builderLab, builder), "append",
					params, new Type.Function(new Type.Clazz("java.lang",
					"StringBuilder"), rhs_t), new Type.Clazz(
							"java.lang", "StringBuilder"));
		} else {
			ArrayList<JilExpr> params = new ArrayList<JilExpr>();
			params.add(rhs.first());
			r = new JilExpr.Invoke(new JilExpr.Variable(builderLab, builder),
					"append", params, new Type.Function(new Type.Clazz(
							"java.lang", "StringBuilder"), new Type.Clazz(
							"java.lang", "Object")), new Type.Clazz(
							"java.lang", "StringBuilder"));
		}

		r = new JilExpr.Invoke(r, "toString", new ArrayList<JilExpr>(),
				new Type.Function(new Type.Clazz("java.lang", "String")),
				new Type.Clazz("java.lang", "String"));
		
		return new Pair<JilExpr,List<JilStmt>>(r,stmts);
	}

	
	protected Pair<JilExpr,List<JilStmt>> doTernOp(jkit.java.tree.Expr.TernOp e) {	
		syntax_error("internal failure --- problem processing ternary operator.",e);
		return null;
	}
	
	protected Scope findEnclosingScope(Class c) {
		for(int i=scopes.size()-1;i>=0;--i) {
			Scope s = scopes.get(i);
			if(s.getClass().equals(c)) {
				return s;
			}
		}
		return null;
	}	
	
	protected boolean superCallFirst(List<JilStmt> stmts) {
		if (stmts.size() == 0 || !(stmts.get(0) instanceof JilExpr.Invoke)) {
			return false;
		} else {
			JilExpr.Invoke sc = (JilExpr.Invoke) stmts.get(0);
			if (!sc.name().equals("super")) {
				return false;
			}
		}
		return true;
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
