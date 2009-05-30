package jkit.bytecode;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import jkit.compiler.ClassLoader;
import jkit.jil.tree.Type;

public class Exceptions implements Attribute {
	protected List<Type.Clazz> exceptions;
	
	public Exceptions(List<Type.Clazz> exceptions) {
		this.exceptions = exceptions;
	}
	
	public String name() {
		return "Exceptions";
	}
	
	public List<Type.Clazz> exceptions() {
		return exceptions;
	}
	
	/**
	 * This method requires the attribute to write itself to the binary stream.
	 * 
	 * @param writer
	 * @returns the number of bytes written.
	 * @throws IOException
	 */
	public void write(BinaryOutputStream writer,
			Map<Constant.Info, Integer> constantPool, ClassLoader loader) throws IOException {
		
		writer.write_u2(constantPool.get(new Constant.Utf8("Exceptions")));
		writer.write_u4(2 + (2 * exceptions.size()));
		writer.write_u2(exceptions.size());
		for (Type.Clazz e : exceptions) {
			writer.write_u2(constantPool.get(Constant.buildClass(e)));
		}
	}
	
	/**
	 * When this method is called, the attribute must add all items that it
	 * needs to the constant pool.
	 * 
	 * @param constantPool
	 */
	public void addPoolItems(Set<Constant.Info> constantPool, ClassLoader loader) {
		Constant.addPoolItem(new Constant.Utf8("Exceptions"), constantPool);
		for (Type.Clazz e : exceptions) {
			Constant.addPoolItem(Constant.buildClass(e), constantPool);
		}
	}
	
	public void print(PrintWriter output, Map<Constant.Info, Integer> constantPool, ClassLoader loader) {
		output.println("  Exceptions:");
		boolean firstTime = true; 
		output.print("   ");
		for(Type.Clazz e : exceptions) {
			if(!firstTime) {
				output.print(", ");
			}
			firstTime=false;
			output.print(e);
		}
		output.println();
	}
}
