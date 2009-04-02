package jkit.java;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import jkit.bytecode.BytecodeFileWriter;
import jkit.bytecode.ClassFile;
import jkit.bytecode.ClassFileBuilder;
import jkit.jil.tree.Clazz;

public class BytecodeCompiler extends JavaCompiler {
	
	/**
	 * @param classpath
	 *            A list of directory and/or jar file locations.
	 */
	public BytecodeCompiler(List<String> classpath) {
		super(classpath);		
	}
	
	/**
	 * @param classpath
	 *            A list of directory and/or jar file locations.
	 * @param logout
	 *            A stream where log messages are sent
	 */
	public BytecodeCompiler(List<String> classpath, OutputStream logout) {
		super(classpath,logout);		
	}
	
	/**
	 * @param sourcepath
	 *            a list of directory and/or jar file locations.
	 * @param classpath
	 *            A list of directory and/or jar file locations.
	 * @param logout
	 *            A stream where log messages are sent
	 */
	public BytecodeCompiler(List<String> sourcepath, List<String> classpath,
			OutputStream logout) {
		super(sourcepath,classpath,logout);
	}
	
	/**
	 * This is the final stage in the compilation pipeline --- we must write the
	 * output file somewhere.
	 * 
	 * @param jfile
	 * @param loader
	 */
	public void writeOutputFile(File srcfile, Clazz clazz, File rootdir)
			throws IOException {
		long start = System.currentTimeMillis();
		
		String inf = srcfile.getPath();
		inf = inf.substring(0, inf.length() - 5); // strip off .java
		File outputFile = new File(rootdir, inf + ".bytecode");		
		
		// now, ensure output directory and package directories exist.
		if(outputFile.getParentFile() != null) {
			outputFile.getParentFile().mkdirs();
		}

		OutputStream out = new FileOutputStream(outputFile);		
		ClassFile cfile = new ClassFileBuilder(loader,49).build(clazz);
		
		logTimedMessage("[" + srcfile.getPath() + "] Bytecode generation completed",
				(System.currentTimeMillis() - start));	
		
		start = System.currentTimeMillis();
		
		// this is where the bytecode optimisation would occur.
		
		logTimedMessage("[" + srcfile.getPath() + "] Bytecode optimisation completed",
				(System.currentTimeMillis() - start));	
		
		start = System.currentTimeMillis();
		
		new BytecodeFileWriter(out).write(cfile);		
		
		logTimedMessage("[" + srcfile.getPath() + "] Wrote " + outputFile.getPath(),
				(System.currentTimeMillis() - start));	
	}
}