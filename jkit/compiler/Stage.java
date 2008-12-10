package jkit.compiler;

import jkit.jil.Clazz;

public interface Stage {
	public String description();
	
	/**
     * Apply this pass to a class.
     * 
     * @param owner
     *            class to manipulate    
     */
	public void apply(Clazz owner);	
}
