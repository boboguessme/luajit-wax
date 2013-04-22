package unluac.decompile.target;

import unluac.decompile.Declaration;
import unluac.decompile.Output;

public class VariableTarget extends Target {

  public final Declaration decl;
  
  public VariableTarget(Declaration decl) {
    this.decl = decl;
  }
  
  @Override
  public void print(Output out) {
    out.print(decl.name);
  }
  
  @Override
  public void printMethod(Output out) {
    throw new IllegalStateException();
  }
  
  @Override
  public boolean isDeclaration(Declaration decl) {
    return this.decl == decl;
  }  
  
  @Override
  public boolean isLocal() {
    return true;
  }
  
  public boolean equals(Object obj) {
    if(obj instanceof VariableTarget) {
      VariableTarget t = (VariableTarget) obj;
      return decl == t.decl;
    } else {
      return false;
    }
  }
  
}
