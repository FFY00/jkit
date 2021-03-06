// This file is part of the Java Compiler Kit (JKit)
//
// The Java Compiler Kit is free software; you can 
// redistribute it and/or modify it under the terms of the 
// GNU General Public License as published by the Free Software 
// Foundation; either version 2 of the License, or (at your 
// option) any later version.
//
// The Java Compiler Kit is distributed in the hope
// that it will be useful, but WITHOUT ANY WARRANTY; without 
// even the implied warranty of MERCHANTABILITY or FITNESS FOR 
// A PARTICULAR PURPOSE.  See the GNU General Public License 
// for more details.
//
// You should have received a copy of the GNU General Public 
// License along with the Java Compiler Kit; if not, 
// write to the Free Software Foundation, Inc., 59 Temple Place, 
// Suite 330, Boston, MA  02111-1307  USA
//
// (C) David James Pearce, 2009. 

package jkit.testing.tests;

import jkit.testing.TestHarness;
import org.junit.*;

public class JikesHPJ extends TestHarness {
	public JikesHPJ() {
		super("tests/jikestst/hpj/src","java","tests/jikestst/hpj/ok/exec","e");
	}
	
	@Test public void BigComp() { runTest("BigComp"); }	
	@Test public void Const() { runTest("Const"); }
	@Test public void DTest() { runTest("DTest"); }

	@Test public void FOSTest() { runTest("FOSTest"); }
		
	@Test public void LoopTest() { runTest("LoopTest"); }	
	@Test public void QuesStmt() { runTest("QuesStmt"); }
	@Test public void RAFTest() { runTest("RAFTest"); }
	@Test public void RefComp() { runTest("RefComp"); }	
	@Test public void Try1() { runTest("Try1"); }
	@Test public void Try2() { runTest("Try2"); }
	@Test public void Try3() { runTest("Try3"); }
	@Test public void Trychk1() { runTest("Trychk1"); }
	@Test public void Trychk2() { runTest("Trychk2"); }
	@Test public void Trychk3() { runTest("Trychk3"); }
	@Test public void Trychk6() { runTest("Trychk6"); }
	@Test public void Trychk7() { runTest("Trychk7"); }
	@Test public void Trychk8() { runTest("Trychk8"); }
	@Test public void Tryexcept() { runTest("Tryexcept"); }
	@Test public void array1() { runTest("array1"); }
	// @Test public void array2() { runTest("array2"); }
	// @Test public void array3() { runTest("array3"); }
	@Test public void array4() { runTest("array4"); }
	@Test public void array5() { runTest("array5"); }
	@Test public void arraymethod() { runTest("arraymethod"); }
	// fails because cannot deal with complex initialisers
	@Ignore("Known Bug") @Test public void bigi() { runTest("bigi"); }
	@Test public void callmm() { runTest("callmm"); }
	@Test public void checkarray() { runTest("checkarray"); }
	@Test public void checkcast1() { runTest("checkcast1"); }
	@Test public void checkcast2() { runTest("checkcast2"); }
	@Test public void checkcast6() { runTest("checkcast6"); }
	@Test public void checkcast7() { runTest("checkcast7"); }
	@Test public void checkcastjp() { runTest("checkcastjp"); }	
	@Test public void classname() { runTest("classname"); }
	// @Test public void clientsock() { runTest("clientsock"); }
	@Test public void clinitrep() { runTest("clinitrep"); }
	@Test public void cmplx1() { runTest("cmplx1"); }
	@Test public void cmplx2() { runTest("cmplx2"); }
	@Test public void cnvi2b_1() { runTest("cnvi2b_1"); }
	@Test public void cnvi2b_2() { runTest("cnvi2b_2"); }
	@Test public void cnvi2c_1() { runTest("cnvi2c_1"); }
	@Test public void cnvi2c_2() { runTest("cnvi2c_2"); }
	@Test public void cnvi2l_1() { runTest("cnvi2l_1"); }
	@Test public void cnvi2l_2() { runTest("cnvi2l_2"); }
	@Test public void cnvi2s_1() { runTest("cnvi2s_1"); }
	@Test public void cnvi2s_2() { runTest("cnvi2s_2"); }
	@Test public void cnvl2i_1() { runTest("cnvl2i_1"); }
	@Test public void cont1() { runTest("cont1"); }
	@Test public void cont2() { runTest("cont2"); }
	@Test public void ctestinit() { runTest("ctestinit"); }
	// @Test public void dgram1() { runTest("dgram1"); }
	// @Test public void dgram2() { runTest("dgram2"); }
	// not sure why this fails; bug in generating bytecode somewhere.
	@Ignore("Known Bug") @Test public void float1() { runTest("float1"); }
	@Test public void for1() { runTest("for1"); }
	@Test public void for2() { runTest("for2"); }
	@Test public void implement() { runTest("implement"); } 
	// fails because of some problem with type checking instanceof statements.
	@Ignore("Known Bug") @Test public void instance() { runTest("instance"); }
	// fails because of some problem with type checking instanceof statements.
	@Ignore("Known Bug") @Test public void instance1() { runTest("instance1"); }
	@Test public void lptry1() { runTest("lptry1"); }
	@Test public void lptry2() { runTest("lptry2"); }	
	@Test public void multarg() { runTest("multarg"); }
	@Test public void multmain() { runTest("multmain"); }
	@Test public void recur() { runTest("recur"); }
	// @Test public void serversock() { runTest("serversock"); }
	@Test public void shift() { runTest("shift"); }
	@Test public void simparray() { runTest("simparray"); }
	@Test public void syncm1() { runTest("syncm1"); }
	@Test public void testtrains() { runTest("testtrains"); }
	@Test public void truckarray() { runTest("truckarray"); }
	@Test public void while1() { runTest("while1"); }
	@Test public void while2() { runTest("while2"); }
}
