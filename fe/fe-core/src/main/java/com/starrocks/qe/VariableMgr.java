// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/qe/VariableMgr.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.qe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.starrocks.analysis.SetType;
import com.starrocks.analysis.SetVar;
import com.starrocks.analysis.SysVariableDesc;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.FeMetaVersion;
import com.starrocks.common.PatternMatcher;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.GlobalVarPersistInfo;
import org.apache.commons.lang.SerializationUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Variable manager, merge session variable and global variable.
 * <p>
 * There are two types of variables, SESSION and GLOBAL.
 * <p>
 * The GLOBAL variable is more like a system configuration, which takes effect globally.
 * The settings for global variables are global and persistent.
 * After the cluster is restarted, the set values still be restored.
 * The global variables are defined in `GlobalVariable`.
 * The variable of the READ_ONLY attribute cannot be changed,
 * and the variable of the GLOBAL attribute can be changed at runtime.
 * <p>
 * Session variables are session-level, and the scope of these variables is usually
 * in a session connection. The session variables are defined in `SessionVariable`.
 * <p>
 * For the setting of the global variable, the value of the field in the `GlobalVariable` class
 * will be modified directly through the reflection mechanism of Java.
 * <p>
 * For the setting of session variables, there are also two types: Global and Session.
 * <p>
 * 1. Use `set global` comment to set session variables
 * <p>
 * This setting method is equivalent to changing the default value of the session variable.
 * It will modify the `defaultSessionVariable` member.
 * This operation is persistent and global. After the setting is complete, when a new session
 * is established, this default value will be used to generate session-level session variables.
 * This operation will also affect the value of the variable in the current session.
 * <p>
 * 2. Use the `set` comment (no global) to set the session variable
 * <p>
 * This setting method will only change the value of the variable in the current session.
 * After the session ends, this setting will also become invalid.
 */
public class VariableMgr {
    private static final Logger LOG = LogManager.getLogger(VariableMgr.class);

    // variable have this flag means that every session have a copy of this variable,
    // and can modify its own variable.
    public static final int SESSION = 1;
    // Variables with this flag have only one instance in one process.
    public static final int GLOBAL = 2;
    // Variables with this flag only exist in each session.
    public static final int SESSION_ONLY = 4;
    // Variables with this flag can only be read.
    public static final int READ_ONLY = 8;
    // Variables with this flag can not be seen with `SHOW VARIABLES` statement.
    public static final int INVISIBLE = 16;

    // Map variable name to variable context which have enough information to change variable value.
    // This map contains info of all session and global variables.
    private static ImmutableMap<String, VarContext> ctxByVarName;

    private static ImmutableMap<String, String> aliases;

    // This variable is equivalent to the default value of session variables.
    // Whenever a new session is established, the value in this object is copied to the session-level variable.
    private static SessionVariable defaultSessionVariable;

    // Global read/write lock to protect access of globalSessionVariable.
    private static final ReadWriteLock rwlock = new ReentrantReadWriteLock();
    private static final Lock rlock = rwlock.readLock();
    private static final Lock wlock = rwlock.writeLock();

    // Form map from variable name to its field in Java class.
    static {
        // Session value
        defaultSessionVariable = new SessionVariable();
        ImmutableSortedMap.Builder<String, VarContext> ctx_builder =
                ImmutableSortedMap.orderedBy(String.CASE_INSENSITIVE_ORDER);
        ImmutableSortedMap.Builder<String, String> alias_builder =
                ImmutableSortedMap.orderedBy(String.CASE_INSENSITIVE_ORDER);
        for (Field field : SessionVariable.class.getDeclaredFields()) {
            VarAttr attr = field.getAnnotation(VarAttr.class);
            if (attr == null) {
                continue;
            }

            if (StringUtils.isNotBlank(attr.show())) {
                Preconditions.checkState((attr.show().equals(attr.name()) || attr.show().equals(attr.alias())),
                        "Session variables show is not equal name or alias");
            }

            field.setAccessible(true);
            ctx_builder.put(attr.name(),
                    new VarContext(field, defaultSessionVariable, SESSION | attr.flag(),
                            getValue(defaultSessionVariable, field), attr));

            if (!attr.alias().isEmpty()) {
                alias_builder.put(attr.alias(), attr.name());
            }
        }

        // Variables only exist in global environment.
        for (Field field : GlobalVariable.class.getDeclaredFields()) {
            VarAttr attr = field.getAnnotation(VarAttr.class);
            if (attr == null) {
                continue;
            }

            field.setAccessible(true);
            ctx_builder.put(attr.name(),
                    new VarContext(field, null, GLOBAL | attr.flag(), getValue(null, field), attr));

            if (!attr.alias().isEmpty()) {
                alias_builder.put(attr.alias(), attr.name());
            }
        }

        ctxByVarName = ctx_builder.build();
        aliases = alias_builder.build();
    }

    public static SessionVariable getDefaultSessionVariable() {
        return defaultSessionVariable;
    }

    // Set value to a variable
    private static boolean setValue(Object obj, Field field, String value) throws DdlException {
        VarAttr attr = field.getAnnotation(VarAttr.class);
        String convertedVal = VariableVarConverters.convert(attr.name(), value);
        try {
            switch (field.getType().getSimpleName()) {
                case "boolean":
                    if (convertedVal.equalsIgnoreCase("ON")
                            || convertedVal.equalsIgnoreCase("TRUE")
                            || convertedVal.equalsIgnoreCase("1")) {
                        field.setBoolean(obj, true);
                    } else if (convertedVal.equalsIgnoreCase("OFF")
                            || convertedVal.equalsIgnoreCase("FALSE")
                            || convertedVal.equalsIgnoreCase("0")) {
                        field.setBoolean(obj, false);
                    } else {
                        throw new IllegalAccessException();
                    }
                    break;
                case "byte":
                    field.setByte(obj, Byte.parseByte(convertedVal));
                    break;
                case "short":
                    field.setShort(obj, Short.parseShort(convertedVal));
                    break;
                case "int":
                    field.setInt(obj, Integer.parseInt(convertedVal));
                    break;
                case "long":
                    field.setLong(obj, Long.parseLong(convertedVal));
                    break;
                case "float":
                    field.setFloat(obj, Float.parseFloat(convertedVal));
                    break;
                case "double":
                    field.setDouble(obj, Double.parseDouble(convertedVal));
                    break;
                case "String":
                    field.set(obj, convertedVal);
                    break;
                default:
                    // Unsupported type variable.
                    ErrorReport.reportDdlException(ErrorCode.ERR_WRONG_TYPE_FOR_VAR, attr.name());
            }
        } catch (NumberFormatException e) {
            ErrorReport.reportDdlException(ErrorCode.ERR_WRONG_TYPE_FOR_VAR, attr.name());
        } catch (IllegalAccessException e) {
            ErrorReport.reportDdlException(ErrorCode.ERR_WRONG_VALUE_FOR_VAR, attr.name(), value);
        }

        return true;
    }

    public static SessionVariable newSessionVariable() {
        wlock.lock();
        try {
            return (SessionVariable) SerializationUtils.clone(defaultSessionVariable);
        } finally {
            wlock.unlock();
        }
    }

    // Check if this setVar can be set correctly
    private static void checkUpdate(SetVar setVar, int flag) throws DdlException {
        if ((flag & READ_ONLY) != 0) {
            ErrorReport.reportDdlException(ErrorCode.ERR_VARIABLE_IS_READONLY, setVar.getVariable());
        }
        if (setVar.getType() == SetType.GLOBAL && (flag & SESSION_ONLY) != 0) {
            ErrorReport.reportDdlException(ErrorCode.ERR_LOCAL_VARIABLE, setVar.getVariable());
        }
        if (setVar.getType() != SetType.GLOBAL && (flag & GLOBAL) != 0) {
            ErrorReport.reportDdlException(ErrorCode.ERR_GLOBAL_VARIABLE, setVar.getVariable());
        }
    }

    // Entry of handling SetVarStmt
    // Input:
    //      sessionVariable: the variable of current session
    //      setVar: variable information that needs to be set
    public static void setVar(SessionVariable sessionVariable, SetVar setVar) throws DdlException {
        VarContext ctx = getVarContext(setVar.getVariable());
        if (ctx == null) {
            ErrorReport.reportDdlException(ErrorCode.ERR_UNKNOWN_SYSTEM_VARIABLE, setVar.getVariable());
        }
        // Check variable attribute and setVar
        checkUpdate(setVar, ctx.getFlag());

        // To modify to default value.
        VarAttr attr = ctx.getField().getAnnotation(VarAttr.class);
        String value;
        // If value is null, this is `set variable = DEFAULT`
        if (setVar.getValue() != null) {
            value = setVar.getValue().getStringValue();
        } else {
            value = ctx.getDefaultValue();
            if (value == null) {
                ErrorReport.reportDdlException(ErrorCode.ERR_NO_DEFAULT, attr.name());
            }
        }

        if (setVar.getType() == SetType.GLOBAL) {
            wlock.lock();
            try {
                setValue(ctx.getObj(), ctx.getField(), value);
                // write edit log
                GlobalVarPersistInfo info =
                        new GlobalVarPersistInfo(defaultSessionVariable, Lists.newArrayList(attr.name()));
                EditLog editLog = Catalog.getCurrentCatalog().getEditLog();
                editLog.logGlobalVariableV2(info);
            } finally {
                wlock.unlock();
            }
        }

        // set session variable
        setValue(sessionVariable, ctx.getField(), value);
    }

    // global variable persistence
    public static void write(DataOutputStream out) throws IOException {
        defaultSessionVariable.write(out);
        // get all global variables
        List<String> varNames = GlobalVariable.getAllGlobalVarNames();
        GlobalVarPersistInfo info = new GlobalVarPersistInfo(defaultSessionVariable, varNames);
        info.write(out);
    }

    public static void read(DataInputStream in) throws IOException, DdlException {
        wlock.lock();
        try {
            defaultSessionVariable.readFields(in);
            if (Catalog.getCurrentCatalogJournalVersion() >= FeMetaVersion.VERSION_90) {
                GlobalVarPersistInfo info = GlobalVarPersistInfo.read(in);
                replayGlobalVariableV2(info);
            }
        } finally {
            wlock.unlock();
        }
    }

    @Deprecated
    private static void writeGlobalVariableUpdate(SessionVariable variable, String msg) {
        EditLog editLog = Catalog.getCurrentCatalog().getEditLog();
        editLog.logGlobalVariable(variable);
    }

    @Deprecated
    public static void replayGlobalVariable(SessionVariable variable) throws DdlException {
        wlock.lock();
        try {
            for (Field field : SessionVariable.class.getDeclaredFields()) {
                VarAttr attr = field.getAnnotation(VarAttr.class);
                if (attr == null) {
                    continue;
                }

                field.setAccessible(true);

                VarContext ctx = getVarContext(attr.name());
                if (ctx.getFlag() == SESSION) {
                    String value = getValue(variable, ctx.getField());
                    setValue(ctx.getObj(), ctx.getField(), value);
                }
            }
        } finally {
            wlock.unlock();
        }
    }

    // this method is used to replace the `replayGlobalVariable()`
    public static void replayGlobalVariableV2(GlobalVarPersistInfo info) throws DdlException {
        wlock.lock();
        try {
            String json = info.getPersistJsonString();
            JSONObject root = new JSONObject(json);
            for (String varName : root.keySet()) {
                VarContext varContext = getVarContext(varName);
                if (varContext == null) {
                    LOG.error("failed to get global variable {} when replaying", varName);
                    continue;
                }
                setValue(varContext.getObj(), varContext.getField(), root.get(varName).toString());
            }
        } finally {
            wlock.unlock();
        }
    }

    // Get variable value through variable name, used to satisfy statement like `SELECT @@comment_version`
    public static void fillValue(SessionVariable var, SysVariableDesc desc) throws AnalysisException {
        VarContext ctx = getVarContext(desc.getName());
        if (ctx == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_UNKNOWN_SYSTEM_VARIABLE, desc.getName());
        }

        if (desc.getSetType() == SetType.GLOBAL) {
            rlock.lock();
            try {
                fillValue(ctx.getObj(), ctx.getField(), desc);
            } finally {
                rlock.unlock();
            }
        } else {
            fillValue(var, ctx.getField(), desc);
        }
    }

    private static void fillValue(Object obj, Field field, SysVariableDesc desc) {
        try {
            switch (field.getType().getSimpleName()) {
                case "boolean":
                    desc.setType(Type.BOOLEAN);
                    desc.setBoolValue(field.getBoolean(obj));
                    break;
                case "byte":
                    desc.setType(Type.TINYINT);
                    desc.setIntValue(field.getByte(obj));
                    break;
                case "short":
                    desc.setType(Type.SMALLINT);
                    desc.setIntValue(field.getShort(obj));
                    break;
                case "int":
                    desc.setType(Type.INT);
                    desc.setIntValue(field.getInt(obj));
                    break;
                case "long":
                    desc.setType(Type.BIGINT);
                    desc.setIntValue(field.getLong(obj));
                    break;
                case "float":
                    desc.setType(Type.FLOAT);
                    desc.setFloatValue(field.getFloat(obj));
                    break;
                case "double":
                    desc.setType(Type.DOUBLE);
                    desc.setFloatValue(field.getDouble(obj));
                    break;
                case "String":
                    desc.setType(Type.VARCHAR);
                    desc.setStringValue((String) field.get(obj));
                    break;
                default:
                    desc.setType(Type.VARCHAR);
                    desc.setStringValue("");
                    break;
            }
        } catch (IllegalAccessException e) {
            LOG.warn("Access failed.", e);
        }
    }

    // Get variable value through variable name, used to satisfy statement like `SELECT @@comment_version`
    public static String getValue(SessionVariable var, SysVariableDesc desc) throws AnalysisException {
        VarContext ctx = getVarContext(desc.getName());
        if (ctx == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_UNKNOWN_SYSTEM_VARIABLE, desc.getName());
        }

        if (desc.getSetType() == SetType.GLOBAL) {
            rlock.lock();
            try {
                return getValue(ctx.getObj(), ctx.getField());
            } finally {
                rlock.unlock();
            }
        } else {
            return getValue(var, ctx.getField());
        }
    }

    private static String getValue(Object obj, Field field) {
        try {
            switch (field.getType().getSimpleName()) {
                case "boolean":
                    return Boolean.toString(field.getBoolean(obj));
                case "byte":
                    return Byte.toString(field.getByte(obj));
                case "short":
                    return Short.toString(field.getShort(obj));
                case "int":
                    return Integer.toString(field.getInt(obj));
                case "long":
                    return Long.toString(field.getLong(obj));
                case "float":
                    return Float.toString(field.getFloat(obj));
                case "double":
                    return Double.toString(field.getDouble(obj));
                case "String":
                    return (String) field.get(obj);
                default:
                    return "";
            }
        } catch (IllegalAccessException e) {
            LOG.warn("Access failed.", e);
        }
        return "";
    }

    // Dump all fields. Used for `show variables`
    public static List<List<String>> dump(SetType type, SessionVariable sessionVar, PatternMatcher matcher) {
        List<List<String>> rows = Lists.newArrayList();
        // Hold the read lock when session dump, because this option need to access global variable.
        rlock.lock();
        try {
            for (Map.Entry<String, VarContext> entry : ctxByVarName.entrySet()) {
                // Filter variable not match to the regex.
                String name = StringUtils.isBlank(entry.getValue().getVarAttr().show()) ? entry.getKey()
                        : entry.getValue().getVarAttr().show();

                if (matcher != null && !matcher.match(name)) {
                    continue;
                }
                VarContext ctx = entry.getValue();

                List<String> row = Lists.newArrayList();

                row.add(name);
                if (type != SetType.GLOBAL && ctx.getObj() == defaultSessionVariable) {
                    // In this condition, we may retrieve session variables for caller.
                    row.add(getValue(sessionVar, ctx.getField()));
                } else {
                    row.add(getValue(ctx.getObj(), ctx.getField()));
                }

                if (row.size() > 1 && row.get(0).equalsIgnoreCase(SessionVariable.SQL_MODE)) {
                    try {
                        row.set(1, SqlModeHelper.decode(Long.valueOf(row.get(1))));
                    } catch (DdlException e) {
                        row.set(1, "");
                        LOG.warn("Decode sql mode failed");
                    }
                }

                rows.add(row);
            }
        } finally {
            rlock.unlock();
        }

        // Sort all variables by variable name.
        Collections.sort(rows, new Comparator<List<String>>() {
            @Override
            public int compare(List<String> o1, List<String> o2) {
                return o1.get(0).compareTo(o2.get(0));
            }
        });

        return rows;
    }

    @Retention(RetentionPolicy.RUNTIME)
    public static @interface VarAttr {
        // Name in show variables and set statement;
        String name();

        String alias() default "";

        // Use in show variables, must keep same with name or alias
        String show() default "";

        int flag() default 0;

        // TODO(zhaochun): min and max is not used.
        String minValue() default "0";

        String maxValue() default "0";
    }

    private static class VarContext {
        private Field field;
        private Object obj;
        private int flag;
        private String defaultValue;
        private VarAttr varAttr;

        public VarContext(Field field, Object obj, int flag, String defaultValue, VarAttr varAttr) {
            this.field = field;
            this.obj = obj;
            this.flag = flag;
            this.defaultValue = defaultValue;
            this.varAttr = varAttr;
        }

        public Field getField() {
            return field;
        }

        public Object getObj() {
            return obj;
        }

        public int getFlag() {
            return flag;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public VarAttr getVarAttr() {
            return varAttr;
        }
    }

    private static VarContext getVarContext(String name) {
        VarContext ctx = ctxByVarName.get(name);
        if (ctx == null) {
            ctx = ctxByVarName.get(aliases.get(name));
        }
        return ctx;
    }
}
