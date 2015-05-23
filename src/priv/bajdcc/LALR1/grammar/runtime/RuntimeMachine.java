package priv.bajdcc.LALR1.grammar.runtime;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;

import priv.bajdcc.LALR1.grammar.Grammar;
import priv.bajdcc.LALR1.grammar.runtime.RuntimeException.RuntimeError;
import priv.bajdcc.LALR1.grammar.runtime.data.RuntimeFuncObject;
import priv.bajdcc.LALR1.grammar.type.TokenTools;
import priv.bajdcc.util.HashListMapEx;
import priv.bajdcc.util.lexer.token.OperatorType;

/**
 * 【虚拟机】运行时自动机
 *
 * @author bajdcc
 */
public class RuntimeMachine implements IRuntimeStack, IRuntimeStatus {

	private HashListMapEx<String, RuntimeCodePage> pageMap = new HashListMapEx<String, RuntimeCodePage>();
	private Map<String, ArrayList<RuntimeCodePage>> pageRefer = new HashMap<String, ArrayList<RuntimeCodePage>>();
	private Stack<RuntimeObject> stkYieldData = new Stack<RuntimeObject>();
	private RuntimeStack stack = new RuntimeStack();

	private RuntimeCodePage currentPage = null;
	private String pageName = null;
	protected boolean debug = false;

	public void run(String name, InputStream input) throws Exception {
		run(name, RuntimeCodePage.importFromStream(input));
	}

	@Override
	public void runPage(String name) throws Exception {
		BufferedReader br = new BufferedReader(new FileReader(name));
		StringBuilder sb = new StringBuilder();
		String line = null;
		while ((line = br.readLine()) != null) {
			sb.append(line);
			sb.append(System.getProperty("line.separator"));
		}
		br.close();
		Grammar grammar = new Grammar(sb.toString());
		run(name, grammar.getCodePage());
	}

	public void add(String name, RuntimeCodePage page) throws Exception {
		if (pageMap.contains(name)) {
			throw new RuntimeException(RuntimeError.DUP_PAGENAME, -1, "请更改名称");
		}
		pageMap.add(name, page);
		pageRefer.put(name, new ArrayList<RuntimeCodePage>());
		pageRefer.get(name).add(page);
		page.getInfo().getDataMap().put("name", name);
	}

	public void run(String name, RuntimeCodePage page) throws Exception {
		add(name, page);
		currentPage = page;
		stack.reg.pageId = name;
		stack.reg.execId = 0;
		switchPage();
		runInsts();
	}

	private void runInsts() throws Exception {
		while (runByStep())
			;
	}

	private boolean runByStep() throws Exception {
		RuntimeInst inst = RuntimeInst.values()[currentInst()];
		if (inst == RuntimeInst.ihalt) {
			return false;
		}
		if (debug) {
			System.err.println();
			System.err.print(stack.reg.execId + ": " + inst.toString());
		}
		OperatorType op = TokenTools.ins2op(inst);
		nextInst();
		if (op != null) {
			if (!RuntimeTools.calcOp(stack.reg, inst, this)) {
				err(RuntimeError.WRONG_OPERTAOR);
			}
		} else {
			if (!RuntimeTools.calcData(stack.reg, inst, this)) {
				if (!RuntimeTools.calcJump(stack.reg, inst, this)) {
					err(RuntimeError.WRONG_INST);
				}
			}
		}
		if (debug) {
			System.err.println();
			System.err.print(stack.toString());
			System.err.print("协程栈：");
			System.err.print(stkYieldData.toString());
			System.err.println();
		}
		return true;
	}

	@Override
	public RuntimeObject load() throws RuntimeException {
		if (stack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		return stack.popData();
	}

	@Override
	public void store(RuntimeObject obj) {
		stack.pushData(obj);
	}

	private RuntimeObject dequeue() throws RuntimeException {
		if (stkYieldData.isEmpty()) {
			err(RuntimeError.NULL_QUEUE);
		}
		return stkYieldData.pop();
	}

	private void enqueue(RuntimeObject obj) {
		stkYieldData.add(obj);
	}

	public RuntimeObject top() throws RuntimeException {
		if (stack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		return stack.top();
	}

	private int loadInt() throws RuntimeException {
		RuntimeObject obj = load();
		if (!(obj.getObj() instanceof Integer)) {
			err(RuntimeError.WRONG_OPERTAOR);
		}
		return (int) obj.getObj();
	}

	private boolean loadBool() throws RuntimeException {
		RuntimeObject obj = load();
		if (!(obj.getObj() instanceof Boolean)) {
			err(RuntimeError.WRONG_OPERTAOR);
		}
		return (boolean) obj.getObj();
	}

	private boolean loadBoolRetain() throws RuntimeException {
		RuntimeObject obj = top();
		if (!(obj.getObj() instanceof Boolean)) {
			err(RuntimeError.WRONG_OPERTAOR);
		}
		return (boolean) obj.getObj();
	}

	@Override
	public void push() throws RuntimeException {
		RuntimeObject obj = new RuntimeObject(current());
		obj.setReadonly(true);
		store(obj);
		next();
	}

	@Override
	public void pop() throws RuntimeException {
		if (stack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		stack.popData();
	}

	private void nextInst() throws RuntimeException {
		stack.reg.execId++;
		if (!available()) {
			err(RuntimeError.WRONG_CODEPAGE);
		}
	}

	private void next() throws RuntimeException {
		if (debug) {
			System.err.print(" " + current());
		}
		stack.reg.execId += 4;
		if (!available()) {
			err(RuntimeError.WRONG_CODEPAGE);
		}
	}

	private void err(RuntimeError type) throws RuntimeException {
		// System.err.println(stack);
		throw new RuntimeException(type, stack.reg.execId, type.getMessage());
	}

	private void switchPage() throws RuntimeException {
		if (!stack.reg.pageId.isEmpty()) {
			currentPage = pageMap.get(stack.reg.pageId);
			pageName = currentPage.getInfo().getDataMap().get("name")
					.toString();
		} else {
			err(RuntimeError.WRONG_CODEPAGE);
		}
	}

	private Byte getInst(int pc) throws RuntimeException {
		List<Byte> code = currentPage.getInsts();
		if (pc < 0 || pc >= code.size()) {
			err(RuntimeError.WRONG_INST);
		}
		return code.get(pc);
	}

	private Byte currentInst() throws RuntimeException {
		if (stack.reg.execId != -1) {
			return getInst(stack.reg.execId);
		} else {
			return (byte) RuntimeInst.ihalt.ordinal();
		}
	}

	private int current() throws RuntimeException {
		int op = 0;
		byte b;
		for (int i = 0; i < 4; i++) {
			b = getInst(stack.reg.execId + i);
			op += (b & 0xFF) << (8 * i);
		}
		return op;
	}

	private boolean available() {
		return stack.reg.execId >= 0
				&& stack.reg.execId < currentPage.getInsts().size();
	}

	private RuntimeObject fetchFromGlobalData(int index)
			throws RuntimeException {
		if (index < 0 || index >= currentPage.getData().size()) {
			err(RuntimeError.WRONG_OPERTAOR);
		}
		return new RuntimeObject(currentPage.getData().get(index));
	}

	@Override
	public void opLoad() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = fetchFromGlobalData(idx);
		stack.pushData(obj);
	}

	@Override
	public void opLoadFunc() throws RuntimeException {
		int idx = loadInt();
		RuntimeFuncObject func = new RuntimeFuncObject(pageName, idx);
		RuntimeObject obj = new RuntimeObject(func);
		int envSize = loadInt();
		for (int i = 0; i < envSize; i++) {
			int id = loadInt();
			func.addEnv(id, stack.findVariable(id));
		}
		stack.pushData(obj);
	}

	@Override
	public void opStore() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = load();
		RuntimeObject target = stack.findVariable(idx);
		if (target == null) {
			err(RuntimeError.WRONG_OPERTAOR);
		}
		if (target.isReadonly()) {
			err(RuntimeError.READONLY_VAR);
		}
		target.copyFrom(obj);
		store(target);
	}

	@Override
	public void opStoreDirect() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = load();
		obj.setReadonly(false);
		stack.storeVariableDirect(idx, obj);
		store(obj);
	}

	@Override
	public void opOpenFunc() throws RuntimeException {
		if (!stack.pushFuncData()) {
			err(RuntimeError.STACK_OVERFLOW);
		}
	}

	@Override
	public void opLoadArgs() throws RuntimeException {
		int idx = current();
		next();
		if (idx < 0 || idx >= stack.getFuncArgsCount()) {
			err(RuntimeError.WRONG_OPERTAOR);
		}
		store(stack.loadFuncArgs(idx));
	}

	@Override
	public void opPushArgs() throws RuntimeException {
		RuntimeObject obj = load();
		obj.setReadonly(true);
		if (!stack.pushFuncArgs(obj)) {
			err(RuntimeError.ARG_OVERFLOW);
		}
	}

	@Override
	public void opReturn() throws RuntimeException {
		if (stack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		stack.opReturn(stack.reg);
		switchPage();
	}

	@Override
	public void opCall() throws RuntimeException {
		int address = loadInt();
		stack.opCall(address, pageName, stack.reg.execId, pageName, currentPage
				.getInfo().getFuncNameByAddress(address));
		stack.reg.execId = address;
		stack.reg.pageId = pageName;
	}

	@Override
	public void opPushNull() {
		store(new RuntimeObject(null, true, false));
	}

	@Override
	public void opPushZero() {
		store(new RuntimeObject(0, true, false));
	}

	@Override
	public void opPushNan() {
		store(new RuntimeObject(null, RuntimeObjectType.kNan, true, false));
	}

	@Override
	public void opLoadVar() throws RuntimeException {
		int idx = loadInt();
		store(RuntimeObject.createObject((stack.findVariable(idx))));
	}

	@Override
	public void opJump() throws RuntimeException {
		stack.reg.execId = current();
	}

	@Override
	public void opJumpBool(boolean bool) throws RuntimeException {
		boolean tf = loadBool();
		if (!(tf ^ bool)) {
			stack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpBoolRetain(boolean bool) throws RuntimeException {
		boolean tf = loadBoolRetain();
		if (!(tf ^ bool)) {
			stack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpZero(boolean bool) throws RuntimeException {
		int val = loadInt();
		if (!((val == 0) ^ bool)) {
			stack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpYield() throws RuntimeException {
		String hash = RuntimeTools.getYieldHash(stack.level,
				stack.getFuncLevel(), pageName, stack.reg.execId - 1);
		if (stack.getYieldStack(hash) != null) {
			stack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpNan() throws RuntimeException {
		RuntimeObject obj = top();
		if (obj.getType() == RuntimeObjectType.kNan) {
			stack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opImport() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = fetchFromGlobalData(idx);
		RuntimeCodePage page = pageMap.get(obj.getObj().toString());
		if (page == null) {
			err(RuntimeError.WRONG_IMPORT);
		}
		pageRefer.get(pageName).add(page);
	}

	@Override
	public void opLoadExtern() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = fetchFromGlobalData(idx);
		String name = obj.getObj().toString();
		List<RuntimeCodePage> refers = pageRefer.get(currentPage.getInfo()
				.getDataMap().get("name"));
		for (RuntimeCodePage page : refers) {
			IRuntimeDebugValue value = page.getInfo().getValueCallByName(name);
			if (value != null) {
				store(value.getRuntimeObject());
				return;
			}
		}
		err(RuntimeError.WRONG_LOAD_EXTERN);
	}

	@Override
	public void opCallExtern(boolean invoke) throws Exception {
		int idx = loadInt();
		String name = "";
		if (invoke) {
			RuntimeObject obj = stack.findVariable(idx);
			if (obj.getType() == RuntimeObjectType.kFunc) {
				RuntimeFuncObject func = (RuntimeFuncObject) obj.getObj();
				Map<Integer, RuntimeObject> env = func.getEnv();
				if (env != null) {
					for (Entry<Integer, RuntimeObject> entry : env.entrySet()) {
						stack.storeClosure(entry.getKey(), entry.getValue());
					}
					stack.pushData(obj);
				}
				int address = func.getAddr();
				stack.opCall(address, func.getPage(), stack.reg.execId,
						pageName, pageMap.get(func.getPage()).getInfo()
								.getFuncNameByAddress(address));
				stack.reg.execId = address;
				stack.reg.pageId = func.getPage();
				switchPage();
				pop();
				return;
			} else if (obj.getType() == RuntimeObjectType.kString) {
				name = obj.getObj().toString();
			} else {
				err(RuntimeError.WRONG_LOAD_EXTERN);
			}
		} else {
			RuntimeObject obj = fetchFromGlobalData(idx);
			name = obj.getObj().toString();
		}
		List<RuntimeCodePage> refers = pageRefer.get(pageName);
		for (RuntimeCodePage page : refers) {
			int address = page.getInfo().getAddressOfExportFunc(name);
			if (address != -1) {
				String jmpPage = page.getInfo().getDataMap().get("name")
						.toString();
				stack.opCall(address, jmpPage, stack.reg.execId,
						stack.reg.pageId, name);
				stack.reg.execId = address;
				stack.reg.pageId = jmpPage;
				switchPage();
				return;
			}
		}
		for (RuntimeCodePage page : refers) {
			IRuntimeDebugExec exec = page.getInfo().getExecCallByName(name);
			if (exec != null) {
				int argsCount = stack.getFuncArgsCount();
				RuntimeObjectType[] types = exec.getArgsType();
				if ((types == null && argsCount != 0)
						|| (types != null && types.length != argsCount)) {
					err(RuntimeError.WRONG_ARGCOUNT);
				}
				ArrayList<RuntimeObject> args = new ArrayList<RuntimeObject>();
				for (int i = 0; i < argsCount; i++) {
					if (types != null) {
						RuntimeObjectType type = types[i];
						if (type != RuntimeObjectType.kObject) {
							RuntimeObject objParam = stack.loadFuncArgs(i);
							RuntimeObjectType objType = objParam.getType();
							if (objType != type) {
								err(RuntimeError.WRONG_ARGTYPE);
							}
						}
					}
					args.add(stack.loadFuncArgs(i));
				}
				stack.opCall(stack.reg.execId, stack.reg.pageId,
						stack.reg.execId, stack.reg.pageId, name);
				RuntimeObject retVal = exec.ExternalProcCall(args, this);
				if (retVal == null) {
					store(new RuntimeObject(null));
				} else {
					store(retVal);
				}
				opReturn();
				return;
			}
		}
		err(RuntimeError.WRONG_LOAD_EXTERN);
	}

	@Override
	public String getHelpString(String name) {
		List<RuntimeCodePage> refers = pageRefer.get(pageName);
		for (RuntimeCodePage page : refers) {
			IRuntimeDebugExec exec = page.getInfo().getExecCallByName(name);
			if (exec != null) {
				String doc = exec.getDoc();
				return doc == null ? "过程无文档" : doc;
			}
		}
		return "过程不存在";
	}

	@Override
	public int getFuncAddr(String name) throws RuntimeException {
		List<RuntimeCodePage> refers = pageRefer.get(pageName);
		for (RuntimeCodePage page : refers) {
			int address = page.getInfo().getAddressOfExportFunc(name);
			if (address != -1) {
				return address;
			}
		}
		err(RuntimeError.WRONG_FUNCNAME);
		return -1;
	}

	@Override
	public void opYield(boolean input) throws RuntimeException {
		if (input) {
			enqueue(load());
		} else {
			store(dequeue());
		}
	}

	@Override
	public void opYieldSwitch(boolean forward) throws RuntimeException {
		if (forward) {
			int yldLine = current();
			next();
			String hash = RuntimeTools.getYieldHash(stack.level,
					stack.getFuncLevel(), pageName, yldLine);
			RuntimeStack newStack = stack.getYieldStack(hash);
			if (newStack != null) {
				stack = newStack;
			} else {
				err(RuntimeError.WRONG_COROUTINE);
			}
		} else {
			if (stack.prev == null) {
				err(RuntimeError.WRONG_COROUTINE);
			}
			stack = stack.prev;
		}
		switchPage();
	}

	private int loadYieldData() throws RuntimeException {
		int size = stkYieldData.size();
		while (!stkYieldData.isEmpty()) {
			opYield(false);
		}
		return size;
	}

	private void loadYieldArgs(int argsSize) throws RuntimeException {
		for (int i = 0; i < argsSize; i++) {
			opPushArgs();
		}
	}

	@Override
	public void opYieldCreateContext() throws Exception {
		RuntimeStack newStack = new RuntimeStack(stack);
		int yldLine = current();
		next();
		String hash = RuntimeTools.getYieldHash(stack.level,
				stack.getFuncLevel(), pageName, yldLine);
		stack.addYieldStack(hash, newStack);
		stack = newStack;
		int yieldSize = loadYieldData();
		int type = loadInt();
		opOpenFunc();
		loadYieldArgs(yieldSize - 2);
		switch (type) {
		case 1:
			opCall();
			break;
		case 2:
			opCallExtern(true);
			break;
		case 3:
			opCallExtern(false);
			break;
		default:
			break;
		}
	}

	@Override
	public void opYieldDestroyContext() throws RuntimeException {
		stack.popYieldStack();
	}

	@Override
	public void opScope(boolean enter) throws RuntimeException {
		if (enter) {
			stack.enterScope();
		} else {
			stack.leaveScope();
		}
	}
}