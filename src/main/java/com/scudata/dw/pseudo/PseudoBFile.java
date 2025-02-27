package com.scudata.dw.pseudo;

import java.util.ArrayList;
import java.util.List;

import com.scudata.common.RQException;
import com.scudata.common.Types;
import com.scudata.dm.Context;
import com.scudata.dm.Record;
import com.scudata.dm.Sequence;
import com.scudata.dm.cursor.BFileCursor;
import com.scudata.dm.cursor.ICursor;
import com.scudata.dm.cursor.MultipathCursors;
import com.scudata.dm.op.Join;
import com.scudata.dm.op.New;
import com.scudata.dm.op.Operation;
import com.scudata.dm.op.Switch;
import com.scudata.expression.Expression;
import com.scudata.expression.Node;
import com.scudata.expression.UnknownSymbol;

/**
 * 集文件虚表类
 * @author LW
 *
 */
public class PseudoBFile extends PseudoTable {
	public PseudoBFile() {
	}
	
	/**
	 * 产生虚表对象
	 * @param rec 定义记录
	 * @param hs 分机序列
	 * @param n 并行数
	 * @param ctx
	 */
	public PseudoBFile(Record rec, int n, Context ctx) {
		pd = new PseudoDefination(rec, ctx);
		pathCount = n;
		this.ctx = ctx;
		extraNameList = new ArrayList<String>();
		init();
	}

	public PseudoBFile(Record rec, PseudoTable mcs, Context ctx) {
		this(rec, 0, ctx);
		mcsTable = mcs;
	}
	
	public PseudoBFile(PseudoDefination pd, int n, Context ctx) {
		this.pd = pd;
		pathCount = n;
		this.ctx = ctx;
		extraNameList = new ArrayList<String>();
		init();
	}

	/**
	 * 得到虚表的每个实体表的游标构成的数组
	 * @return
	 */
	public ICursor[] getCursors() {
		throw new RQException("Never run to here.");
	}
	
	/**
	 * 设置取出字段
	 * @param exps 取出表达式
	 * @param fields 取出别名
	 */
	protected void setFetchInfo_(Expression []exps, String []fields) {
		this.exps = null;
		this.names = null;
		boolean needNew = extraNameList.size() > 0;
		Expression newExps[] = null;
		
		extraOpList.clear();
		
		if (exps == null) {
			if (fields == null) {
				return;
			} else {
				int len = fields.length;
				exps = new Expression[len];
				for (int i = 0; i < len; i++) {
					exps[i] = new Expression(fields[i]);
				}
			}
		}
		
		newExps = exps.clone();//备份一下
		
		/**
		 * 有取出表达式也有取出字段,则检查extraNameList里是否包含exps里的字段
		 * 如果包含就去掉
		 */
		ArrayList<String> tempList = new ArrayList<String>();
		for (String name : extraNameList) {
			if (!tempList.contains(name)) {
				tempList.add(name);
			}
		}
		for (Expression exp : exps) {
			String expName = exp.getIdentifierName();
			if (tempList.contains(expName)) {
				tempList.remove(expName);
			}
		}
		
		ArrayList<String> tempNameList = new ArrayList<String>();
		int size = exps.length;
		for (int i = 0; i < size; i++) {
			Expression exp = exps[i];
			String name = fields[i];
			Node node = exp.getHome();
			
			if (node instanceof UnknownSymbol) {
				String expName = exp.getIdentifierName();
				if (!allNameList.contains(expName)) {
					/**
					 * 如果是伪字段则做转换
					 */
					PseudoColumn col = pd.findColumnByPseudoName(expName);
					if (col != null) {
						if (col.get_enum() != null) {
							/**
							 * 枚举字段做转换
							 */
							String var = "pseudo_enum_value_" + i;
							ctx.setParamValue(var, col.get_enum());
							name = col.getName();
							newExps[i] = new Expression(var + "(" + name + ")");
							exp = new Expression(name);
							needNew = true;
							tempNameList.add(name);
						} else if (col.getBits() != null) {
							/**
							 * 二值字段做转换
							 */
							name = col.getName();
							String pname = ((UnknownSymbol) node).getName();
							Sequence seq;
							seq = col.getBits();
							int idx = seq.firstIndexOf(pname) - 1;
							int bit = 1 << idx;
							String str = "and(" + col.getName() + "," + bit + ")!=0";//改为真字段的位运算
							newExps[i] = new Expression(str);
							exp = new Expression(name);
							needNew = true;
							tempNameList.add(name);
						}
					}
				} else {
					tempNameList.add(name);
				}
			}
		}
		
		for (String name : tempList) {
			if (!tempNameList.contains(name)) {
				tempNameList.add(name);
			}
		}
		
		size = tempNameList.size();
		
		this.names = new String[size];
		tempNameList.toArray(this.names);
	
		
		if (needNew) {
			New _new = new New(newExps, fields, null);
			extraOpList.add(_new);
		}
		return;
	}
	
	public ICursor cursor(Expression []exps, String []names) {
		return cursor(exps, names, false);
	}
	
	//返回虚表的游标
	public ICursor cursor(Expression []exps, String []names, boolean isColumn) {
		ICursor cursor = null;
		setFetchInfo(exps, names);//把取出字段添加进去，里面可能会对extraOpList赋值
		
		if (pathCount > 1) {//指定了并行数
			int count = pathCount;
			ICursor cursors[] = new ICursor[count];
			for (int i = 0; i < count; ++i) {
				if (this.exps == null && this.names == null) {
					cursors[i] = new BFileCursor(pd.getFileObject(), null, i + 1, count, null, ctx);
				} else {
					cursors[i] = new BFileCursor(pd.getFileObject(), this.names, i + 1, count, null, ctx);
				}
			}
			cursor = new MultipathCursors(cursors, ctx);
		} else {
			if (this.exps == null && this.names == null) {
				cursor = new BFileCursor(pd.getFileObject(), null, null, ctx);
			} else {
				cursor = new BFileCursor(pd.getFileObject(), this.names, null, ctx);
			}
		}

		List<PseudoColumn> list = getFieldSwitchColumns(this.names);
		if (getPd() != null && list != null) {
			for (PseudoColumn column : list) {
				if (column.getDim() != null) {//如果存在外键，则添加一个switch的延迟计算
					Sequence dim;
					if (column.getDim() instanceof Sequence) {
						dim = (Sequence) column.getDim();
					} else {
						dim = ((IPseudo) column.getDim()).cursor(null, null, false).fetch();
					}
					
					String fkey[] = column.getFkey();
					/**
					 * 如果fkey等于null，且name不等于null，且存在time，则组织为一个新的fkey={name，time}
					 */
					if (fkey == null && column.getName() != null && column.getTime() != null) {
						fkey = new String[] {column.getName(), column.getTime()};
					}
					
					if (fkey == null) {
						/**
						 * 此时name就是外键字段
						 */
						String[] fkNames = new String[] {column.getName()};
						Sequence[] codes = new Sequence[] {dim};
						Switch s = new Switch(fkNames, codes, null, null);
						cursor.addOperation(s, ctx);
//					} else if (fkey.length == 1) {
//						Sequence[] codes = new Sequence[] {dim};
//						Switch s = new Switch(fkey, codes, null, null);
//						cursor.addOperation(s, ctx);
					} else {
						int size = fkey.length;
						
						/**
						 * 如果定义了时间字段,就把时间字段拼接到fkey末尾
						 */
						if (column.getTime() != null) {
							size++;
							fkey = new String[size];
							System.arraycopy(column.getFkey(), 0, fkey, 0, size - 1);
							fkey[size - 1] = column.getTime();
						}
						
						Expression[][] exps_ = new Expression[1][];
						exps_[0] = new Expression[size];
						for (int i = 0; i < size; i++) {
							exps_[0][i] = new Expression(fkey[i]);
						}
						Expression[][] newExps = new Expression[1][];
						newExps[0] = new Expression[] {new Expression("~")};
						String[][] newNames = new String[1][];
						newNames[0] = new String[] {column.getPseudo()};
						
						Expression[][] dimKeyExps = new Expression[1][];
						String[] dimKey = column.getDimKey();
						if (dimKey == null) {
							dimKeyExps[0] = null;
						} else {
							Expression[] dimKeyExp = new Expression[size];
							for (int i = 0; i < size; i++) {
								dimKeyExp[i] = new Expression(dimKey[i]);
							}
							dimKeyExps[0] = dimKeyExp;
						}
						
						Join join = new Join(null, null, exps_, new Sequence[] {dim}, dimKeyExps, newExps, newNames, null);
						cursor.addOperation(join, ctx);
					}
				}
			}
		}
		if (opList != null) {
			for (Operation op : opList) {
				cursor.addOperation(op, ctx);
			}
		}
		if (extraOpList != null) {
			for (Operation op : extraOpList) {
				cursor.addOperation(op, ctx);
			}
		}
		
		return cursor;
	}

	public Object clone(Context ctx) throws CloneNotSupportedException {
		PseudoBFile obj = new PseudoBFile();
		obj.hasPseudoColumns = hasPseudoColumns;
		obj.pathCount = pathCount;
		obj.mcsTable = mcsTable;
		obj.fkNames = fkNames == null ? null : fkNames.clone();
		obj.codes = codes == null ? null : codes.clone();
		cloneField(obj);
		obj.ctx = ctx;
		return obj;
	}
	
	public void append(ICursor cursor, String option) {
		pd.getFileObject().exportCursor(cursor, null, null, "ab", null, ctx);
	}
	
	public Sequence update(Sequence data, String opt) {
		throw new RQException("Never run to here."); 
	}
	
	public Sequence delete(Sequence data, String opt) {
		throw new RQException("Never run to here.");
	}
	
	public Pseudo addForeignKeys(String fkName, String []fieldNames, Object code, String[] codeKeys, boolean clone) {
		PseudoBFile table = null;
		try {
			table = clone ? (PseudoBFile) clone(ctx) : this;
			table.getPd().addPseudoColumn(new PseudoColumn(fkName, fieldNames, code, codeKeys));
//			if (fieldNames == null) {
//				table.addColName(fkName);
//			} else {
//				for (String key : fieldNames) {
//					table.addColName(key);
//				}
//			}
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		}
		return table;
	}
	
	/**
	 * 获得虚表对应的组表的每列的数据类型
	 * 注意：返回的类型是以第一条记录为准
	 * @return
	 */
	public byte[] getFieldTypes() {
		ICursor cursor = new BFileCursor(pd.getFileObject(), null, null, ctx);
		Sequence data = cursor.fetch(1);
		cursor.close();
		
		if (data == null || data.length() == 0) {
			return null;
		}
		
		Record record = (Record) data.getMem(1);
		Object[] objs = record.getFieldValues();
		int len = objs.length;
		byte[] types = new byte[len];
		
		for (int i = 0; i < len; i++) {
			types[i] = Types.getProperDataType(objs[i]);
		}
		return types;
	}
}
