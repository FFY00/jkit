/*
 * Copyright (C) 2002 Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * California 95054, U.S.A. All rights reserved.  Sun Microsystems, Inc. has
 * intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation,
 * these intellectual property rights may include one or more of the U.S.
 * patents listed at http://www.sun.com/patents and one or more additional
 * patents or pending patent applications in the U.S. and in other countries.
 * U.S. Government Rights - Commercial software. Government users are subject
 * to the Sun Microsystems, Inc. standard license agreement and applicable
 * provisions of the FAR and its supplements.  Use is subject to license terms.
 * Sun,  Sun Microsystems,  the Sun logo and  Java are trademarks or registered
 * trademarks of Sun Microsystems, Inc. in the U.S. and other countries.  This
 * product is covered and controlled by U.S. Export Control laws and may be
 * subject to the export or import laws in other countries.  Nuclear, missile,
 * chemical biological weapons or nuclear maritime end uses or end users,
 * whether direct or indirect, are strictly prohibited.  Export or reexport
 * to countries subject to U.S. embargo or to entities identified on U.S.
 * export exclusion lists, including, but not limited to, the denied persons
 * and specially designated nationals lists is strictly prohibited.
 */

package org.javacc.jjtree;

import java.io.*;
import java.util.Vector;

import org.javacc.parser.JavaCCGlobals;

final class IO
{
  private String ifn;
  private String ofn;
  private Reader in;
  private PrintWriter out;
  private PrintStream msg;
  private PrintStream err;

  private Vector toolList;

  IO()
  {
    ifn = "<uninitialized input>";
    ofn = "<uninitialized output>";
    msg = System.out;
    err = System.err;
  }

  String getInputFileName()
  {
    return ifn;
  }

  Reader getIn()
  {
    return in;
  }

  String getOutputFileName()
  {
    return ofn;
  }

  PrintWriter getOut()
  {
    return out;
  }

  PrintStream getMsg()
  {
    return msg;
  }

  PrintStream getErr()
  {
    return err;
  }


  void print(String s)
  {
    out.print(s);
  }

  void println(String s)
  {
    out.println(s);
  }

  void println()
  {
    out.println();
  }


  void closeAll()
  {
    if (out != null) out.close();
    if (msg != null) msg.flush();
    if (err != null) err.flush();
  }
  



  private String create_output_file_name(String i, char sep) {
    String o = org.javacc.parser.Options.S("OUTPUT_FILE");
    String d = org.javacc.parser.Options.S("OUTPUT_DIRECTORY");

    if (o.equals("")) {
      int s = i.lastIndexOf(sep);
      if (s >= 0) {
	i = i.substring(s + 1);
      }

      int di = i.lastIndexOf('.');
      if (di == -1) {
	o = i + ".jj";
      } else {
	String suffix = i.substring(di);
	if (suffix.equals(".jj")) {
	  o  = i + ".jj";
	} else {
	  o = i.substring(0, di) + ".jj";
	}
      }
    }

    if (d.equals("")) {
      return o;
    } else {
      return d + sep + o;
    }
  }


  void setInput(String fn) throws JJTreeIOException
  {
    try {
      File fp = new File(fn);
      if (!fp.exists()) {
	throw new JJTreeIOException("File " + fn + " not found.");
      }
      if (fp.isDirectory()) {
	throw new JJTreeIOException(fn + " is a directory. Please use a valid file name.");
      }
      if (org.javacc.parser.JavaCCGlobals.isGeneratedBy("JJTree", fn)) {
	throw new JJTreeIOException(fn + " was generated by jjtree.  Cannot run jjtree again.");
      }
      toolList = JavaCCGlobals.getToolNames(fn);

      ifn = fp.getPath();
      ofn = create_output_file_name(ifn, fp.separatorChar);

      in = new FileReader(ifn);
      
    } catch (NullPointerException ne) { // Should never happen
      throw new JJTreeIOException(ne.toString());
    } catch (SecurityException se) {
      throw new JJTreeIOException("Security violation while trying to open " + fn);
    } catch (FileNotFoundException e) {
      throw new JJTreeIOException("File " + fn + " not found.");
    } catch (IOException ioe) {
      throw new JJTreeIOException(ioe.toString());
    }

    try {
      out = new PrintWriter(new FileWriter(ofn));
    } catch (IOException fnf) {
      throw new JJTreeIOException("Can't create output file " + ofn);
    }
  }

}

/*end*/
