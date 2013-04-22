package unluac.decompile.block;

import java.util.ArrayList;
import java.util.List;

import unluac.decompile.Output;
import unluac.decompile.statement.Statement;

public class OuterBlock extends Block {

  private final List<Statement> statements;
  
  public OuterBlock(int length) {
    super(0, length + 1);
    statements = new ArrayList<Statement>(length);
  }

  @Override
  public void addStatement(Statement statement) {
    statements.add(statement);
  }
  
  @Override
  public boolean breakable() {
    return false;
  }
  
  @Override
  public boolean isContainer() {
    return true;
  }
  
  @Override
  public boolean isUnprotected() {
    return false;
  }
  
  @Override
  public int getLoopback() {
    throw new IllegalStateException();
  }
  
  @Override
  public int scopeEnd() {
    return end - 2;
  }
  
  @Override
  public void print(Output out) {
    /* extra return statement */
    statements.remove(statements.size() - 1);
    Statement.printSequence(out, statements);
  }
  
}
