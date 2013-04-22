package unluac.decompile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import unluac.decompile.block.AlwaysLoop;
import unluac.decompile.block.Block;
import unluac.decompile.block.BooleanIndicator;
import unluac.decompile.block.Break;
import unluac.decompile.block.CompareBlock;
import unluac.decompile.block.DoEndBlock;
import unluac.decompile.block.ElseEndBlock;
import unluac.decompile.block.ForBlock;
import unluac.decompile.block.IfThenElseBlock;
import unluac.decompile.block.IfThenEndBlock;
import unluac.decompile.block.OuterBlock;
import unluac.decompile.block.RepeatBlock;
import unluac.decompile.block.SetBlock;
import unluac.decompile.block.TForBlock;
import unluac.decompile.block.WhileBlock;
import unluac.decompile.branch.AndBranch;
import unluac.decompile.branch.AssignNode;
import unluac.decompile.branch.Branch;
import unluac.decompile.branch.EQNode;
import unluac.decompile.branch.LENode;
import unluac.decompile.branch.LTNode;
import unluac.decompile.branch.OrBranch;
import unluac.decompile.branch.TestNode;
import unluac.decompile.branch.TestSetNode;
import unluac.decompile.expression.BinaryExpression;
import unluac.decompile.expression.ClosureExpression;
import unluac.decompile.expression.ConstantExpression;
import unluac.decompile.expression.Expression;
import unluac.decompile.expression.FunctionCall;
import unluac.decompile.expression.GlobalExpression;
import unluac.decompile.expression.LocalVariable;
import unluac.decompile.expression.TableLiteral;
import unluac.decompile.expression.TableReference;
import unluac.decompile.expression.UnaryExpression;
import unluac.decompile.expression.UpvalueExpression;
import unluac.decompile.expression.Vararg;
import unluac.decompile.operation.CallOperation;
import unluac.decompile.operation.GlobalSet;
import unluac.decompile.operation.Operation;
import unluac.decompile.operation.RegisterSet;
import unluac.decompile.operation.ReturnOperation;
import unluac.decompile.operation.TableSet;
import unluac.decompile.operation.UpvalueSet;
import unluac.decompile.statement.Assignment;
import unluac.decompile.statement.Declare;
import unluac.decompile.statement.FunctionCallStatement;
import unluac.decompile.statement.Return;
import unluac.decompile.statement.Statement;
import unluac.decompile.target.GlobalTarget;
import unluac.decompile.target.TableTarget;
import unluac.decompile.target.Target;
import unluac.decompile.target.UpvalueTarget;
import unluac.decompile.target.VariableTarget;
import unluac.parse.LBoolean;
import unluac.parse.LFunction;
import unluac.parse.LNil;
import unluac.util.Stack;

public class Decompiler {

  public static final int MOVE = 0;
  public static final int LOADK = 1;
  public static final int LOADBOOL = 2;
  public static final int LOADNIL = 3;
  public static final int GETUPVAL = 4;
  public static final int GETGLOBAL = 5;
  public static final int GETTABLE = 6;
  public static final int SETGLOBAL = 7;
  public static final int SETUPVAL = 8;
  public static final int SETTABLE = 9;
  public static final int NEWTABLE = 10;
  public static final int SELF = 11;
  public static final int ADD = 12;
  public static final int SUB = 13;
  public static final int MUL = 14;
  public static final int DIV = 15;
  public static final int MOD = 16;
  public static final int POW = 17;
  public static final int UNM = 18;
  public static final int NOT = 19;
  public static final int LEN = 20;
  public static final int CONCAT = 21;
  public static final int JMP = 22;
  public static final int EQ = 23;
  public static final int LT = 24;
  public static final int LE = 25;
  public static final int TEST = 26;
  public static final int TESTSET = 27;
  public static final int CALL = 28;
  public static final int TAILCALL = 29;
  public static final int RETURN = 30;
  public static final int FORLOOP = 31;
  public static final int FORPREP = 32;
  public static final int TFORLOOP = 33;
  public static final int SETLIST = 34;
  public static final int CLOSE = 35;
  public static final int CLOSURE = 36;
  public static final int VARARG = 37;
    
  private final Output out;
  private final int registers;
  private final int length;
  public final Code code;
  private final Constant[] constants;
  private final String[] upvalues;
  private final Declaration[] declList;
  
  private final LFunction[] functions;  
  private final int params;
  private final int vararg;
  
  public Decompiler(LFunction function) {
    this(function, new Output());
  }
  
  public Decompiler(LFunction function, OutputProvider out) {
    this(function, new Output(out));
  }
  
  public Decompiler(LFunction function, Output out) {
    this.out = out;
    registers = function.maximumStackSize;
    length = function.code.length;
    code = new Code(function.code);
    constants = new Constant[function.constants.length];
    for(int i = 0; i < constants.length; i++) {
      constants[i] = new Constant(function.constants[i]);
    }
    declList = new Declaration[function.locals.length];
    for(int i = 0; i < declList.length; i++) {
      declList[i] = new Declaration(function.locals[i]);
    }
    upvalues = function.upvalues;
    functions = function.functions;
    params = function.numParams;
    vararg = function.vararg;
  }
  
  private Registers r;
  
  public void decompile() {
    r = new Registers(registers, length, declList, constants);
    handleInitialDeclares();
    findReverseTargets();
    handleBranches(true);
    Block outer = handleBranches(false);
    processSequence(1, length);
    outer.print(out);
  }
  
  private void handleInitialDeclares() {
    List<Declaration> initdecls = new ArrayList<Declaration>(declList.length);
    for(int i = params + (vararg & 1); i < declList.length; i++) {
      if(declList[i].begin == 0) {
        initdecls.add(declList[i]);
      }
    }
    if(initdecls.size() > 0) {
      out.print("local ");
      out.print(initdecls.get(0).name);
      for(int i = 1; i < initdecls.size(); i++) {
        out.print(", ");
        out.print(initdecls.get(i).name);
      }
      out.println();
    }
  }
  
  private List<Operation> processLine(int line) {
    List<Operation> operations = new LinkedList<Operation>();
    int A = code.A(line);
    int B = code.B(line);
    int C = code.C(line);
    int Bx = code.Bx(line);
    switch(code.op(line)) {
      case MOVE:
        operations.add(new RegisterSet(line, A, r.getExpression(B, line)));
        break;
      case LOADK:
        operations.add(new RegisterSet(line, A, new ConstantExpression(constants[Bx], Bx)));
        break;
      case LOADBOOL:
        operations.add(new RegisterSet(line, A, new ConstantExpression(new Constant(B != 0 ? LBoolean.LTRUE : LBoolean.LFALSE), -1)));
        break;
      case LOADNIL:
        while(A <= B) {
          operations.add(new RegisterSet(line, A, Expression.NIL));
          A++;
        }
        break;
      case GETUPVAL:
        operations.add(new RegisterSet(line, A, new UpvalueExpression(upvalues[B])));
        break;
      case GETGLOBAL:
        operations.add(new RegisterSet(line, A, new GlobalExpression(constants[Bx].asName(), Bx)));
        break;
      case GETTABLE:
        operations.add(new RegisterSet(line, A, new TableReference(r.getExpression(B, line), r.getKExpression(C, line))));
        break;
      case SETUPVAL:
        operations.add(new UpvalueSet(line, upvalues[B], r.getExpression(A, line)));
        break;
      case SETGLOBAL:
        operations.add(new GlobalSet(line, constants[Bx].asName(), r.getExpression(A, line)));
        break;
      case SETTABLE:
        operations.add(new TableSet(line, r.getExpression(A, line), r.getKExpression(B, line), r.getKExpression(C, line), true, line));
        break;
      case NEWTABLE:
        operations.add(new RegisterSet(line, A, new TableLiteral(B, C)));
        break;
      case SELF: {
        // We can later determine is : syntax was used by comparing subexpressions with ==
        Expression common = r.getExpression(B, line);
        operations.add(new RegisterSet(line, A + 1, common));
        operations.add(new RegisterSet(line, A, new TableReference(common, r.getKExpression(C, line))));
        break;
      }
      case ADD:
        operations.add(new RegisterSet(line, A, Expression.makeADD(r.getKExpression(B, line), r.getKExpression(C, line))));
        break;
      case SUB:
        operations.add(new RegisterSet(line, A, Expression.makeSUB(r.getKExpression(B, line), r.getKExpression(C, line))));
        break;
      case MUL:
        operations.add(new RegisterSet(line, A, Expression.makeMUL(r.getKExpression(B, line), r.getKExpression(C, line))));
        break;
      case DIV:
        operations.add(new RegisterSet(line, A, Expression.makeDIV(r.getKExpression(B, line), r.getKExpression(C, line))));
        break;
      case MOD:
        operations.add(new RegisterSet(line, A, Expression.makeMOD(r.getKExpression(B, line), r.getKExpression(C, line))));
        break;
      case POW:
        operations.add(new RegisterSet(line, A, Expression.makePOW(r.getKExpression(B, line), r.getKExpression(C, line))));
        break;
      case UNM:
        operations.add(new RegisterSet(line, A, Expression.makeUNM(r.getExpression(B, line))));
        break;
      case NOT:
        operations.add(new RegisterSet(line, A, Expression.makeNOT(r.getExpression(B, line))));
        break;
      case LEN:
        operations.add(new RegisterSet(line, A, Expression.makeLEN(r.getExpression(B, line))));
        break;
      case CONCAT: {
        Expression value = r.getExpression(C, line);
        //Remember that CONCAT is right associative.
        while(C-- > B) {
          value = Expression.makeCONCAT(r.getExpression(C, line), value);
        }
        operations.add(new RegisterSet(line, A, value));        
        break;
      }
      case JMP:
      case EQ:
      case LT:
      case LE:
      case TEST:
      case TESTSET:
        /* Do nothing ... handled with branches */
        break;
      case CALL: {
        boolean multiple = (C >= 3 || C == 0);
        if(B == 0) B = registers - A;
        if(C == 0) C = registers - A + 1;
        Expression function = r.getExpression(A, line);
        Expression[] arguments = new Expression[B - 1];
        for(int register = A + 1; register <= A + B - 1; register++) {
          arguments[register - A - 1] = r.getExpression(register, line);
        }
        FunctionCall value = new FunctionCall(function, arguments, multiple);
        if(C == 1) {
          operations.add(new CallOperation(line, value));
        } else {
          if(C == 2 && !multiple) {
            operations.add(new RegisterSet(line, A, value));
          } else {
            for(int register = A; register <= A + C - 2; register++) {
              operations.add(new RegisterSet(line, register, value));
            }
          }
        }
        break;
      }
      case TAILCALL: {
        if(B == 0) B = registers - A;
        Expression function = r.getExpression(A, line);
        Expression[] arguments = new Expression[B - 1];
        for(int register = A + 1; register <= A + B - 1; register++) {
          arguments[register - A - 1] = r.getExpression(register, line);
        }
        FunctionCall value = new FunctionCall(function, arguments, true);
        operations.add(new ReturnOperation(line, value));
        skip[line + 1] = true;
        break;
      }
      case RETURN: {
        if(B == 0) B = registers - A + 1;
        Expression[] values = new Expression[B - 1];
        for(int register = A; register <= A + B - 2; register++) {
          values[register - A] = r.getExpression(register, line);
        }
        operations.add(new ReturnOperation(line, values));
        break;
      }
      case FORLOOP:
      case FORPREP:
      case TFORLOOP:
        /* Do nothing ... handled with branches */
        break;
      case SETLIST: {
        if(C == 0) {
          C = code.codepoint(line + 1);
          skip[line + 1] = true;
        }
        if(B == 0) {
          B = registers - A - 1;
        }
        Expression table = r.getValue(A, line);
        for(int i = 1; i <= B; i++) {
          operations.add(new TableSet(line, table, new ConstantExpression(new Constant((C - 1) * 50 + i), -1), r.getExpression(A + i, line), false, r.getUpdated(A + i, line)));
        }
        break;
      }
      case CLOSE:
        break;
      case CLOSURE: {
        LFunction f = functions[Bx];
        operations.add(new RegisterSet(line, A, new ClosureExpression(f, line + 1)));
        for(int i = 0; i < f.numUpvalues; i++) {
          skip[line + 1 + i] = true;
        }
        break;
      }
      case VARARG: {
        boolean multiple = (B != 2);
        if(B == 1) throw new IllegalStateException();
        if(B == 0) B = registers - A + 1;
        Expression value = new Vararg(B - 1, multiple);
        for(int register = A; register <= A + B - 2; register++) {
          operations.add(new RegisterSet(line, register, value));
        }
        break;
      }
      default:
        throw new IllegalStateException("Illegal instruction: " + code.op(line));
    }
    return operations;
  }
  
  /**
   * When lines are processed out of order, they are noted
   * here so they can be skipped when encountered normally.
   */
  boolean[] skip;

  /**
   * Precalculated array of which lines are the targets of
   * jump instructions that go backwards... such targets
   * must be at the statement/block level in the outputted
   * code (they cannot be mid-expression).
   */
  boolean[] reverseTarget;
  
  private void findReverseTargets() {
    reverseTarget = new boolean[length + 1];
    Arrays.fill(reverseTarget, false);
    for(int line = 1; line <= length; line++) {
      if(code.op(line) == JMP && code.sBx(line) < 0) {
        reverseTarget[line + 1 + code.sBx(line)] = true;
      }
    }
  }
  
  private Assignment processOperation(Operation operation, int line, int nextLine, Block block) {
    Assignment assign = null;
    boolean wasMultiple = false;
    Statement stmt = operation.process(r, block);
    if(stmt != null) {
      if(stmt instanceof Assignment) {
        assign = (Assignment) stmt;
        if(!assign.getFirstValue().isMultiple()) {
          block.addStatement(stmt);
        } else {
          wasMultiple = true;
        }
      } else {
        block.addStatement(stmt);
      }
      //System.out.println("-- added statemtent @" + line);
      if(assign != null) {
        //System.out.println("-- checking for multiassign @" + nextLine);
        while(nextLine < block.end && isMoveIntoTarget(nextLine)) {
          //System.out.println("-- found multiassign @" + nextLine);
          Target target = getMoveIntoTargetTarget(nextLine, line + 1);
          Expression value = getMoveIntoTargetValue(nextLine, line + 1); //updated?
          assign.addFirst(target, value);
          skip[nextLine] = true;
          nextLine++;
        }
        if(wasMultiple && !assign.getFirstValue().isMultiple()) {
          block.addStatement(stmt);
        }
      }
    }
    return assign;
  }
  
  private void processSequence(int begin, int end) {
    int blockIndex = 1;
    Stack<Block> blockStack = new Stack<Block>();
    blockStack.push(blocks.get(0));
    skip = new boolean[end + 1];
    for(int line = begin; line <= end; line++) {
      /*
      System.out.print("-- line " + line + "; R[0] = ");
      r.getValue(0, line).print(new Output());
      System.out.println();
      System.out.print("-- line " + line + "; R[1] = ");
      r.getValue(1, line).print(new Output());
      System.out.println();
      System.out.print("-- line " + line + "; R[2] = ");
      r.getValue(2, line).print(new Output());
      System.out.println();
      */
      Operation blockHandler = null;
      while(blockStack.peek().end <= line) {
        Block block = blockStack.pop();
        blockHandler = block.process(this);
        if(blockHandler != null) {
          break;
        }
      }
      if(blockHandler == null) {
        while(blockIndex < blocks.size() && blocks.get(blockIndex).begin <= line) {
          blockStack.push(blocks.get(blockIndex++));
        }
      }
      Block block = blockStack.peek();
      r.startLine(line); //Must occur AFTER block.rewrite
      if(skip[line]) {
        List<Declaration> newLocals = r.getNewLocals(line);
        if(!newLocals.isEmpty()) {
          Assignment assign = new Assignment();
          assign.declare(newLocals.get(0).begin);
          for(Declaration decl : newLocals) {
            assign.addLast(new VariableTarget(decl), r.getValue(decl.register, line));
          }
          blockStack.peek().addStatement(assign);
        }
        continue;
      }
      List<Operation> operations = processLine(line);
      List<Declaration> newLocals = r.getNewLocals(blockHandler == null ? line : line - 1);
      //List<Declaration> newLocals = r.getNewLocals(line);
      Assignment assign = null;
      if(blockHandler == null) {
        if(code.op(line) == LOADNIL) {
          assign = new Assignment();
          int count = 0;
          for(Operation operation : operations) {
            RegisterSet set = (RegisterSet) operation;
            operation.process(r, block);
            if(r.isAssignable(set.register, set.line)) {
              assign.addLast(r.getTarget(set.register, set.line), set.value);
              count++;
            }
          }
          if(count > 0) {
            block.addStatement(assign);
          }
        } else {
          //System.out.println("-- Process iterating ... ");
          for(Operation operation : operations) {
            //System.out.println("-- iter");
            Assignment temp = processOperation(operation, line, line + 1, block);
            if(temp != null) {
              assign = temp;
              //System.out.print("-- top assign -> "); temp.getFirstTarget().print(out); System.out.println();
            }
          }
          if(assign != null && assign.getFirstValue().isMultiple()) {
            block.addStatement(assign);
          }
        }
      } else {
        assign = processOperation(blockHandler, line, line, block);
      }
      if(assign != null) {
        if(!newLocals.isEmpty()) {
          assign.declare(newLocals.get(0).begin);
          for(Declaration decl : newLocals) {
            //System.out.println("-- adding decl @" + line);
            assign.addLast(new VariableTarget(decl), r.getValue(decl.register, line + 1));
          }
          //blockStack.peek().addStatement(assign);
        }
      }
      if(blockHandler == null) {
        if(assign != null) {
          
        } else if(!newLocals.isEmpty() && code.op(line) != FORPREP) {
          if(code.op(line) != JMP || code.op(line + 1 + code.sBx(line)) != TFORLOOP) {
            assign = new Assignment();
            assign.declare(newLocals.get(0).begin);
            for(Declaration decl : newLocals) {
              assign.addLast(new VariableTarget(decl), r.getValue(decl.register, line));
            }
            blockStack.peek().addStatement(assign);
          }
        }
      }
      if(blockHandler != null) {
        //System.out.println("-- repeat @" + line);
        line--;
        continue;
      }
    }    
  }
  
  private boolean isMoveIntoTarget(int line) {
    switch(code.op(line)) {
      case MOVE:
        return r.isAssignable(code.A(line), line) && !r.isLocal(code.B(line), line);
      case SETUPVAL:
      case SETGLOBAL:
        return !r.isLocal(code.A(line), line);
      case SETTABLE: {
        int C = code.C(line);
        if((C & 0x100) != 0) {
          return false;
        } else {
          return !r.isLocal(C, line);
        }
      }
      default:
        return false;
    }
  }
  
  private Target getMoveIntoTargetTarget(int line, int previous) {
    switch(code.op(line)) {
      case MOVE:
        return r.getTarget(code.A(line), line);
      case SETUPVAL:
        return new UpvalueTarget(upvalues[code.B(line)]);
      case SETGLOBAL:
        return new GlobalTarget(constants[code.Bx(line)].asName());
      case SETTABLE:
        return new TableTarget(r.getExpression(code.A(line), previous), r.getKExpression(code.B(line), previous));
      default:
        throw new IllegalStateException();
    }
  }
  
  private Expression getMoveIntoTargetValue(int line, int previous) {
    int A = code.A(line);
    int B = code.B(line);
    int C = code.C(line);
    switch(code.op(line)) {
      case MOVE:
        return r.getValue(B, previous);
      case SETUPVAL:
      case SETGLOBAL:
        return r.getExpression(A, previous);
      case SETTABLE:
        if((C & 0x100) != 0) {
          throw new IllegalStateException();
        } else {
          return r.getExpression(C, previous);
        }
      default:
        throw new IllegalStateException();
    }
  }
  
  private ArrayList<Block> blocks;
  
  private OuterBlock handleBranches(boolean first) {
    List<Block> oldBlocks = blocks;
    blocks = new ArrayList<Block>();
    OuterBlock outer = new OuterBlock(length);
    blocks.add(outer);
    if(!first) {
      for(Block block : oldBlocks) {
        if(block instanceof AlwaysLoop) {
          blocks.add(block);
        }
        if(block instanceof Break) {
          blocks.add(block);
        }
      }
    }
    skip = new boolean[length + 1];
    Stack<Branch> stack = new Stack<Branch>();
    boolean reduce = false;
    boolean testset = false;
    int testsetend = -1;
    for(int line = 1; line <= length; line++) {
      if(!skip[line]) {
        switch(code.op(line)) {
          case EQ:
            stack.push(new EQNode(code.B(line), code.C(line), code.A(line) != 0, line, line + 2, line + 2 + code.sBx(line + 1)));
            skip[line + 1] = true;
            if(code.op(stack.peek().end) == LOADBOOL) {
              if(code.C(stack.peek().end) != 0) {
                stack.peek().isCompareSet = true;
              } else if(code.op(stack.peek().end - 1) == LOADBOOL) {
                if(code.C(stack.peek().end - 1) != 0) {
                  stack.peek().isCompareSet = true;
                }
              }
            }
            continue;
          case LT:
            stack.push(new LTNode(code.B(line), code.C(line), code.A(line) != 0, line, line + 2, line + 2 + code.sBx(line + 1)));
            skip[line + 1] = true;
            if(code.op(stack.peek().end) == LOADBOOL) {
              if(code.C(stack.peek().end) != 0) {
                stack.peek().isCompareSet = true;
              } else if(code.op(stack.peek().end - 1) == LOADBOOL) {
                if(code.C(stack.peek().end - 1) != 0) {
                  stack.peek().isCompareSet = true;
                }
              }
            }
            continue;
          case LE:
            stack.push(new LENode(code.B(line), code.C(line), code.A(line) != 0, line, line + 2, line + 2 + code.sBx(line + 1)));
            skip[line + 1] = true;
            if(code.op(stack.peek().end) == LOADBOOL) {
              if(code.C(stack.peek().end) != 0) {
                stack.peek().isCompareSet = true;
              } else if(code.op(stack.peek().end - 1) == LOADBOOL) {
                if(code.C(stack.peek().end - 1) != 0) {
                  stack.peek().isCompareSet = true;
                }
              }
            }
            continue;
          case TEST:
            stack.push(new TestNode(code.A(line), code.C(line) != 0, line, line + 2, line + 2 + code.sBx(line + 1)));
            skip[line + 1] = true;
            continue;
          case TESTSET:
            testset = true;
            testsetend = line + 2 + code.sBx(line + 1);
            stack.push(new TestSetNode(code.A(line), code.B(line), code.C(line) != 0, line, line + 2, line + 2 + code.sBx(line + 1)));
            skip[line + 1] = true;            
            continue;
          case JMP: {
            reduce = true;
            int tline = line + 1 + code.sBx(line);
            if(code.op(tline) == TFORLOOP && !skip[tline]) {
              int A = code.A(tline);
              int C = code.C(tline);
              if(C == 0) throw new IllegalStateException();
              r.getDeclaration(A, tline).forLoop = true;
              r.getDeclaration(A + 1, tline).forLoop = true;
              r.getDeclaration(A + 2, tline).forLoop = true;
              for(int index = 1; index <= C; index++) {
                r.getDeclaration(A + 2 + index, line + 1).forLoopExplicit = true;
              }
              skip[tline] = true;
              skip[tline + 1] = true;
              blocks.add(new TForBlock(line + 1, tline + 2, A, C, r));
            } else if(code.sBx(line) == 2 && code.op(line + 1) == LOADBOOL && code.C(line + 1) != 0) {
              /* This is the tail of a boolean set with a compare node and assign node */
              blocks.add(new BooleanIndicator(line));
            } else {
              /*
              for(Block block : blocks) {
                if(!block.breakable() && block.end == tline) {
                  block.end = line;
                }
              }
              */
              if(first) {
                if(tline > line) {
                  blocks.add(new Break(line, tline));
                } else {
                  blocks.add(new AlwaysLoop(tline, line + 1));
                }
              }
            }
            break;
          }
          case FORPREP:
            reduce = true;
            blocks.add(new ForBlock(line + 1, line + 2 + code.sBx(line), code.A(line), r));
            skip[line + 1 + code.sBx(line)] = true;
            r.getDeclaration(code.A(line), line).forLoop = true;
            r.getDeclaration(code.A(line) + 1, line).forLoop = true;
            r.getDeclaration(code.A(line) + 2, line).forLoop = true;
            r.getDeclaration(code.A(line) + 3, line).forLoopExplicit = true;
            break;
          case FORLOOP:
            /* Should be skipped by preceding FORPREP */
            throw new IllegalStateException();
          default:
            reduce = isStatement(line);
            break;
        }
      }

      if((line + 1) <= length && reverseTarget[line + 1]) {
        reduce = true;
      }
      if(testset && testsetend == line + 1) {
        reduce = true;
      }
      if(stack.isEmpty()) {
        reduce = false;
      }
      if(reduce) {
        reduce = false;
        Stack<Branch> conditions = new Stack<Branch>();
        Stack<Stack<Branch>> backups = new Stack<Stack<Branch>>();
        do {
          boolean isAssignNode = stack.peek() instanceof TestSetNode;
          int assignEnd = stack.peek().end;
          boolean compareCorrect = false;
          if(stack.peek().isCompareSet) {
            if(code.op(stack.peek().begin) != LOADBOOL || code.C(stack.peek().begin) == 0) {
              isAssignNode = true;
              if(code.C(assignEnd) != 0) {
                assignEnd += 2;
              } else {
                assignEnd += 1;
              }
              compareCorrect = true;
            }
          } else if(assignEnd - 3 >= 1 && code.op(assignEnd - 2) == LOADBOOL && code.C(assignEnd - 2) != 0 && code.op(assignEnd - 3) == JMP && code.sBx(assignEnd - 3) == 2) {
            isAssignNode = true;
          } else if(assignEnd - 1 >= 1 && r.isLocal(getAssignment(assignEnd - 1), assignEnd - 1) && assignEnd > stack.peek().line) {
            Declaration decl = r.getDeclaration(getAssignment(assignEnd - 1), assignEnd - 1);
            if(decl.begin == assignEnd - 1 && decl.end > assignEnd - 1) {
              isAssignNode = true;
            }
          }
          if(!compareCorrect && assignEnd - 1 == stack.peek().begin && code.op(stack.peek().begin) == LOADBOOL && code.C(stack.peek().begin) != 0) {
            backup = null;
            int begin = stack.peek().begin;
            assignEnd = begin + 2;
            int target = code.A(begin);
            conditions.push(popCompareSetCondition(stack, assignEnd));
            conditions.peek().setTarget = target;
            conditions.peek().end = assignEnd;
            conditions.peek().begin = begin;
          } else if(isAssignNode) {
            backup = null;
            int target = stack.peek().setTarget;
            int begin = stack.peek().begin;
            conditions.push(popSetCondition(stack, assignEnd));
            conditions.peek().setTarget = target;
            conditions.peek().end = assignEnd;
            conditions.peek().begin = begin;
          } else {
            backup = new Stack<Branch>();
            conditions.push(popCondition(stack));
            backup.reverse();
          }
          backups.push(backup);
        } while(!stack.isEmpty());
        do {
          Branch cond = conditions.pop();
          Stack<Branch> backup = backups.pop();
          int breakTarget = breakTarget(cond.begin);
          boolean breakable = (breakTarget >= 1);
          if(breakable && breakTarget == cond.end) {
            Block immediateEnclosing = enclosingBlock(cond.begin);
            for(int iline = immediateEnclosing.end - 1; iline >= immediateEnclosing.begin; iline--) {
              if(code.op(iline) == JMP && iline + 1 + code.sBx(iline) == breakTarget) {
                cond.end = iline;
                break;
              }
            }
          }
          /* A branch has a tail if the instruction just before the end target is JMP */
          boolean hasTail = cond.end >= 2 && code.op(cond.end - 1) == JMP;
          /* This is the target of the tail JMP */
          int tail = hasTail ? cond.end + code.sBx(cond.end - 1) : -1;
          int originalTail = tail;
          Block enclosing = enclosingUnprotectedBlock(cond.begin);
          /* Checking enclosing unprotected block to undo JMP redirects. */
          if(enclosing != null) {
            //System.out.println("loopback: " + enclosing.getLoopback());
            //System.out.println("cond.end: " + cond.end);
            //System.out.println("tail    : " + tail);
            if(enclosing.getLoopback() == cond.end) {
              cond.end = enclosing.end - 1;
              hasTail = cond.end >= 2 && code.op(cond.end - 1) == JMP;
              tail = hasTail ? cond.end + code.sBx(cond.end - 1) : -1;
            }
            if(hasTail && enclosing.getLoopback() == tail) {
              tail = enclosing.end - 1;
            }
          }
          if(cond.isSet) {
            blocks.add(new SetBlock(cond, cond.setTarget, line, cond.begin, cond.end, r));
          } else if(code.op(cond.begin) == LOADBOOL && code.C(cond.begin) != 0) {
            int begin = cond.begin;
            int target = code.A(begin);
            if(code.B(begin) == 0) {
              cond = cond.invert();
            }
            blocks.add(new CompareBlock(begin, begin + 2, target, cond));
          } else if(cond.end < cond.begin) {
            blocks.add(new RepeatBlock(cond, r));
          } else if(hasTail) {
            if(tail > cond.end) {
              int op = code.op(tail - 1);
              int sbx = code.sBx(tail - 1);
              int loopback2 = tail + sbx;
              if((op == FORLOOP || op == JMP) && loopback2 <= cond.begin) {
                /* (ends with break) */
                blocks.add(new IfThenEndBlock(cond, backup, r));
              } else {
                IfThenElseBlock ifthen = new IfThenElseBlock(cond, originalTail, r);
                skip[cond.end - 1] = true; //Skip the JMP over the else block
                ElseEndBlock elseend = new ElseEndBlock(cond.end, tail);
                blocks.add(ifthen);
                blocks.add(elseend);
              }
            } else {
              int loopback = tail;
              skip[cond.end - 1] = true;
              if(loopback >= cond.begin) {
                blocks.add(new IfThenEndBlock(cond, backup, r));
              } else {
                blocks.add(new WhileBlock(cond, originalTail, r));
              }
            }          
          } else {
            blocks.add(new IfThenEndBlock(cond, backup, r));
          }
        } while(!conditions.isEmpty());
      }
    }
    //Find variables whose scope isn't controlled by existing blocks:
    for(Declaration decl : declList) {
      if(!decl.forLoop && !decl.forLoopExplicit) {
        boolean needsDoEnd = true;
        for(Block block : blocks) {
          if(block.contains(decl.begin)) {
            if(block.scopeEnd() == decl.end) {
              needsDoEnd = false;
              break;
            }
          }
        }
        if(needsDoEnd) {
          //Without accounting for the order of declarations, we might
          //create another do..end block later that would eliminate the
          //need for this one. But order of decls should fix this.
          blocks.add(new DoEndBlock(decl.begin, decl.end + 1));
        }
      }
    }
    Collections.sort(blocks);
    backup = null;
    return outer;
  }
  
  private int breakTarget(int line) {
    int tline = Integer.MAX_VALUE;
    for(Block block : blocks) {
      if(block.breakable() && block.contains(line)) {
        tline = Math.min(tline, block.end);
      }
    }
    if(tline == Integer.MAX_VALUE) return -1;
    return tline;
  }
  
  private Block enclosingBlock(int line) {
    //Assumes the outer block is first
    Block outer = blocks.get(0);
    Block enclosing = outer;
    for(int i = 1; i < blocks.size(); i++) {
      Block next = blocks.get(i);
      if(next.isContainer() && enclosing.contains(next) && next.contains(line) && !next.loopRedirectAdjustment) {
        enclosing = next;
      }
    }
    return enclosing;
  }
  
  private Block enclosingBlock(Block block) {
    //Assumes the outer block is first
    Block outer = blocks.get(0);
    Block enclosing = outer;
    for(int i = 1; i < blocks.size(); i++) {
      Block next = blocks.get(i);
      if(next == block) continue;
      if(next.contains(block) && enclosing.contains(next)) {
        enclosing = next;
      }
    }
    return enclosing;
  }
  
  private Block enclosingUnprotectedBlock(int line) {
    //Assumes the outer block is first
    Block outer = blocks.get(0);
    Block enclosing = outer;
    for(int i = 1; i < blocks.size(); i++) {
      Block next = blocks.get(i);
      if(enclosing.contains(next) && next.contains(line) && next.isUnprotected() && !next.loopRedirectAdjustment) {
        enclosing = next;
      }
    }
    return enclosing == outer ? null : enclosing;
  }
  
  private static Stack<Branch> backup;
  
  public static Branch popCondition(Stack<Branch> stack) {
    Branch branch = stack.pop();
    if(backup != null) backup.push(branch);
    if(branch instanceof TestSetNode) {
      throw new IllegalStateException();
    }
    while(!stack.isEmpty()) {
      Branch next = stack.peek();
      if(next instanceof TestSetNode) break;
      if(next.end == branch.begin) {
        branch = new OrBranch(popCondition(stack).invert(), branch);
      } else if(next.end == branch.end) {
        branch = new AndBranch(popCondition(stack), branch);
      } else {
        break;
      }
    }
    return branch;
  }
  
  public Branch popSetCondition(Stack<Branch> stack, int assignEnd) {
    stack.push(new AssignNode(assignEnd - 1, assignEnd, assignEnd));
    //Invert argument doesn't matter because begin == end
    Branch rtn = _helper_popSetCondition(stack, false, assignEnd);
    return rtn;
  }
  
  public Branch popCompareSetCondition(Stack<Branch> stack, int assignEnd) {
    Branch top = stack.pop();
    boolean invert = false;
    if(code.B(top.begin) == 0) invert = true;//top = top.invert();
    top.begin = assignEnd;
    top.end = assignEnd;
    stack.push(top);
    //stack.pop();
    //stack.push(new AssignNode(assignEnd - 1, assignEnd, assignEnd));
    //Invert argument doesn't matter because begin == end
    Branch rtn = _helper_popSetCondition(stack, invert, assignEnd);
    return rtn;
  }
  
  private Branch _helper_popSetCondition(Stack<Branch> stack, boolean invert, int assignEnd) {
    Branch branch = stack.pop();
    int begin = branch.begin;
    int end = branch.end;
    if(invert) {
      branch = branch.invert();
    }
    if(code.op(begin) == LOADBOOL) {
      if(code.C(begin) != 0) {
        begin += 2;
      } else {
        begin += 1;
      }
    }
    if(code.op(end) == LOADBOOL) {
      if(code.C(end) != 0) {
        end += 2;
      } else {
        end += 1;
      }
    }
    int target = branch.setTarget;
    while(!stack.isEmpty()) {
      Branch next = stack.peek();
      boolean ninvert;
      int nend = next.end;
      if(code.op(next.end) == LOADBOOL) {
        ninvert = code.B(next.end) != 0;
        if(code.C(next.end) != 0) {
          nend += 2;
        } else {
          nend += 1;
        }
      } else if(next instanceof TestSetNode) {
        TestSetNode node = (TestSetNode) next;
        ninvert = node.invert;
      } else if(next instanceof TestNode) {
        TestNode node = (TestNode) next;
        ninvert = node.invert;
      } else {
        ninvert = false;
        if(nend >= assignEnd) {
          break;
        }
      }
      int addr;
      if(ninvert == invert) {
        addr = end;
      } else {
        addr = begin;
      }
      
      //System.out.println(" addr: " + addr + "(" + begin + ", " + end + ")");
      //System.out.println(" nend: " + nend);
      //System.out.println(" ninv: " + ninvert);
      //System.out.println("-------------");
      
      if(addr == nend) {
        if(addr != nend) ninvert = !ninvert;
        if(ninvert) {
          branch = new OrBranch(_helper_popSetCondition(stack, ninvert, assignEnd), branch);
        } else {
          branch = new AndBranch(_helper_popSetCondition(stack, ninvert, assignEnd), branch);
        }
        branch.end = nend;
      } else {
        if(!(branch instanceof TestSetNode)) {
          stack.push(branch);
          branch = popCondition(stack);
        }
        //System.out.println("--break");
        break;
      }
    }
    branch.isSet = true;
    branch.setTarget = target;
    return branch;
  }
  
  private boolean isStatement(int line) {
    return isStatement(line, -1);
  }
  
  private boolean isStatement(int line, int testRegister) {
    switch(code.op(line)) {
      case MOVE:
      case LOADK:
      case LOADBOOL:
      case GETUPVAL:
      case GETGLOBAL:
      case GETTABLE:
      case NEWTABLE:
      case ADD:
      case SUB:
      case MUL:
      case DIV:
      case MOD:
      case POW:
      case UNM:
      case NOT:
      case LEN:
      case CONCAT:
      case CLOSURE:
        return r.isLocal(code.A(line), line) || code.A(line) == testRegister;
      case LOADNIL:
        for(int register = code.A(line); register <= code.B(line); register++) {
          if(r.isLocal(register, line)) {
            return true;
          }
        }
        return false;
      case SETGLOBAL:
      case SETUPVAL:
      case SETTABLE:
      case JMP:
      case TAILCALL:
      case RETURN:
      case FORLOOP:
      case FORPREP:
      case TFORLOOP:
      case CLOSE:
        return true;
      case SELF:
        return r.isLocal(code.A(line), line) || r.isLocal(code.A(line) + 1, line);
      case EQ:
      case LT:
      case LE:
      case TEST:
      case TESTSET:
      case SETLIST:
        return false;
      case CALL: {
        int a = code.A(line);
        int c = code.C(line);
        if(c == 1) {
          return true;
        }
        if(c == 0) c = registers - a + 1;
        for(int register = a; register < a + c - 1; register++) {
          if(r.isLocal(register, line)) {
            return true;
          }
        }
        return (c == 2 && a == testRegister);
      }
      case VARARG: {
        int a = code.A(line);
        int b = code.B(line);
        if(b == 0) b = registers - a + 1;
        for(int register = a; register < a + b - 1; register++) {
          if(r.isLocal(register, line)) {
            return true;
          }
        }
        return false;
      }
      default:
        throw new IllegalStateException("Illegal opcode: " + code.op(line));
    }
  }
  
  /**
   * Returns the single register assigned to at the line or
   * -1 if no register or multiple registers is/are assigned to.
   */
  private int getAssignment(int line) {
    switch(code.op(line)) {
      case MOVE:
      case LOADK:
      case LOADBOOL:
      case GETUPVAL:
      case GETGLOBAL:
      case GETTABLE:
      case NEWTABLE:
      case ADD:
      case SUB:
      case MUL:
      case DIV:
      case MOD:
      case POW:
      case UNM:
      case NOT:
      case LEN:
      case CONCAT:
      case CLOSURE:
        return code.A(line);
      case LOADNIL:
        if(code.A(line) == code.B(line)) {
          return code.A(line);
        } else {
          return -1;
        }
      case SETGLOBAL:
      case SETUPVAL:
      case SETTABLE:
      case JMP:
      case TAILCALL:
      case RETURN:
      case FORLOOP:
      case FORPREP:
      case TFORLOOP:
      case CLOSE:
        return -1;
      case SELF:
        return -1;
      case EQ:
      case LT:
      case LE:
      case TEST:
      case TESTSET:
      case SETLIST:
        return -1;
      case CALL: {
        if(code.C(line) == 2) {
          return code.A(line);
        } else {
          return -1;
        }
      }
      case VARARG: {
        if(code.C(line) == 2) {
          return code.B(line);
        } else {
          return -1;
        }
      }
      default:
        throw new IllegalStateException("Illegal opcode: " + code.op(line));
    }
  }
  
}
