package io.opendbapi.core.script;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.transform.ThreadInterrupt;
import groovy.transform.TimedInterrupt;
import io.opendbapi.sql.template.BoundSql;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.runtime.InvokerHelper;

/**
 * Compiles and runs fragment scripts (spec §7.4). Groovy has no reliable sandbox, so this is
 * defence in depth for scripts written by trusted roles, not a boundary for untrusted code:
 * imports, annotations, class and method declarations, {@code new}, method pointers, field
 * access and reflective names are refused at compile time; types must come from a short
 * allow list; subscripts must be literals; the only receiver-less calls are {@code sql},
 * {@code raw} (literal text only) and {@code orderBy}; and every run is time-limited.
 */
public final class ScriptSandbox {

    /** A compiled script; its class loader goes away with it. */
    public static final class Compiled {
        private final Class<?> type;
        private final long timeoutMillis;
        private final Set<String> parameterNames;

        private Compiled(Class<?> type, long timeoutMillis, Set<String> parameterNames) {
            this.type = type;
            this.timeoutMillis = timeoutMillis;
            this.parameterNames = Set.copyOf(parameterNames);
        }

        /** Parameters the script reads as {@code params.name} or {@code params['name']}. */
        public Set<String> parameterNames() {
            return parameterNames;
        }

        /**
         * @throws ScriptRejected when the script fails, runs out of time or returns no fragment
         */
        public BoundSql render(Map<String, Object> params) {
            Object result = execute(params, null);
            if (!(result instanceof SqlFragment fragment)) {
                throw new ScriptRejected("The script must return a fragment built with sql\"…\", not "
                        + (result == null ? "nothing" : result.getClass().getSimpleName()) + ".");
            }
            return fragment.toBoundSql();
        }

        /**
         * Runs a full-mode script with {@code db} (spec §7.4) and returns what it returned.
         *
         * @throws ScriptAnswer      when the script answered with notFound/badRequest
         * @throws DbAccess.Failure  when its database work failed
         * @throws ScriptRejected    when the script failed or ran out of time
         */
        public Object run(Map<String, Object> params, DbAccess db) {
            return execute(params, db);
        }

        private Object execute(Map<String, Object> params, DbAccess db) {
            Binding binding = new Binding();
            binding.setVariable("params", Collections.unmodifiableMap(new LinkedHashMap<>(params)));
            if (db != null) {
                binding.setVariable("db", db);
            }
            Script script = InvokerHelper.createScript(type, binding);
            try {
                return script.run();
            } catch (ScriptRejected | ScriptAnswer | DbAccess.Failure e) {
                throw e;
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    if (root instanceof ScriptAnswer || root instanceof DbAccess.Failure || root instanceof ScriptRejected) {
                        break;
                    }
                    root = root.getCause();
                }
                if (root instanceof ScriptAnswer a) {
                    throw a;
                }
                if (root instanceof DbAccess.Failure f) {
                    throw f;
                }
                if (root instanceof ScriptRejected r) {
                    throw r;
                }
                if (root instanceof TimeoutException) {
                    throw new ScriptRejected("The script ran longer than %d ms.".formatted(timeoutMillis));
                }
                throw new ScriptRejected("The script failed: " + root.getClass().getSimpleName()
                        + (root.getMessage() == null ? "" : ": " + firstLine(root.getMessage())));
            } catch (StackOverflowError e) {
                throw new ScriptRejected("The script recursed too deeply.");
            }
        }
    }

    /** Fragment scripts return the query; full scripts run queries through {@code db} and return any value. */
    public enum ScriptMode { FRAGMENT, FULL }

    static final Set<String> RECEIVERLESS_FULL = Set.of("sql", "raw", "orderBy", "notFound", "badRequest");

    /** The only methods a full script may call on {@code db}. */
    static final Set<String> DB_METHODS = Set.of("one", "list", "value", "update");

    static final Set<String> RECEIVERLESS = Set.of("sql", "raw", "orderBy");

    /**
     * The only methods a script may call on a value. Groovy adds hundreds of methods to every
     * object (file and URL helpers among them), so names are allowed, not denied.
     */
    static final Set<String> ALLOWED_METHODS = Set.of(
            // text
            "toUpperCase", "toLowerCase", "trim", "strip", "length", "isEmpty", "isBlank", "startsWith", "endsWith", "contains",
            "substring", "replace", "replaceAll", "split", "tokenize", "indexOf", "lastIndexOf", "matches", "padLeft", "padRight",
            "capitalize", "uncapitalize", "toInteger", "toLong", "toBigDecimal", "toDouble", "toBoolean", "isNumber", "isInteger",
            "isLong", "isBigDecimal", "charAt", "concat", "equalsIgnoreCase", "repeat",
            // general
            "equals", "compareTo", "toString", "hashCode", "plus", "minus", "multiply", "div", "mod", "negative", "abs", "round",
            "intValue", "longValue", "doubleValue", "max", "min", "floor", "ceil", "sqrt", "pow", "signum",
            // collections
            "size", "each", "eachWithIndex", "collect", "collectEntries", "find", "findAll", "findResult", "any", "every", "inject",
            "sum", "sort", "unique", "reverse", "first", "last", "take", "drop", "takeWhile", "dropWhile", "join", "count", "groupBy",
            "keySet", "values", "entrySet", "containsKey", "containsValue", "get", "getOrDefault", "put", "add", "addAll", "remove",
            "toList", "toSet", "asList", "withIndex", "indexed", "flatten", "intersect", "disjoint", "subList", "isCase", "leftShift",
            "getKey", "getValue",
            // dates
            "plusDays", "minusDays", "plusMonths", "minusMonths", "plusYears", "minusYears", "plusHours", "minusHours",
            "isBefore", "isAfter", "getYear", "getMonthValue", "getDayOfMonth", "toLocalDate", "atStartOfDay", "parse", "of",
            "valueOf");

    /** Names that reach reflection, class loading, the runtime or the script's own machinery. */
    static final Set<String> DENIED_NAMES = Set.of("getClass", "class", "metaClass", "getMetaClass", "setMetaClass", "invokeMethod",
            "getProperty", "setProperty", "getAt", "putAt", "properties", "getProperties", "binding", "getBinding", "setBinding",
            "evaluate", "run", "execute", "exec", "classLoader", "getClassLoader", "forName", "newInstance", "invoke", "getMethod",
            "getMethods", "getDeclaredMethod", "getDeclaredMethods", "getField", "getFields", "getDeclaredField", "getDeclaredFields",
            "getConstructor", "getConstructors", "getDeclaredConstructor", "getDeclaredConstructors", "methods", "declaredMethods",
            "fields", "declaredFields", "constructors", "declaredConstructors", "delegate", "owner", "thisObject", "getDelegate",
            "getOwner", "getThisObject", "setDelegate", "rehydrate", "dehydrate", "identity", "with", "tap", "sleep", "wait", "notify",
            "notifyAll", "finalize", "getRuntime", "exit", "halt", "getenv", "load", "loadLibrary", "mixin", "asType", "withTraits",
            "this", "super", "out", "System", "Runtime", "Thread", "ProcessBuilder", "Class", "ClassLoader", "Eval", "GroovyShell",
            "metaPropertyValues", "hasProperty", "respondsTo", "getMetaPropertyValues", "invokeMethodMissing", "methodMissing",
            "propertyMissing", "context", "protectionDomain", "module", "getModule", "unwrap",
            // values that read files, URLs or class resources through Groovy's added properties
            "TYPE", "text", "bytes", "url", "uri", "content", "inputStream", "outputStream", "reader", "writer", "resource",
            "resourceAsStream", "file", "path", "parentFile", "absolutePath", "canonicalPath", "declaringClass", "componentType",
            "superclass", "interfaces", "enclosingClass", "packageName", "package");

    static final Set<String> ALLOWED_TYPES = java.util.stream.Stream.of(ClassHelper.OBJECT_TYPE, ClassHelper.DYNAMIC_TYPE, ClassHelper.STRING_TYPE,
            ClassHelper.GSTRING_TYPE, ClassHelper.Integer_TYPE, ClassHelper.int_TYPE, ClassHelper.Long_TYPE, ClassHelper.long_TYPE,
            ClassHelper.Short_TYPE, ClassHelper.short_TYPE, ClassHelper.Double_TYPE, ClassHelper.double_TYPE, ClassHelper.Float_TYPE,
            ClassHelper.float_TYPE, ClassHelper.Boolean_TYPE, ClassHelper.boolean_TYPE, ClassHelper.BigDecimal_TYPE,
            ClassHelper.BigInteger_TYPE, ClassHelper.Number_TYPE, ClassHelper.LIST_TYPE, ClassHelper.MAP_TYPE,
            ClassHelper.make(ArrayList.class), ClassHelper.make(LinkedHashMap.class), ClassHelper.make(HashMap.class),
            ClassHelper.make(Set.class), ClassHelper.make(LinkedHashSet.class), ClassHelper.make(Collection.class),
            ClassHelper.make(CharSequence.class), ClassHelper.make(Math.class), ClassHelper.make(SqlFragment.class),
            ClassHelper.make(LocalDate.class), ClassHelper.make(LocalDateTime.class), ClassHelper.make(OffsetDateTime.class),
            ClassHelper.make(BigDecimal.class), ClassHelper.make(BigInteger.class), ClassHelper.VOID_TYPE, ClassHelper.void_WRAPPER_TYPE)
            .map(ClassNode::getName).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final long timeoutMillis;

    public ScriptSandbox(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** @throws ScriptRejected with the line of the first problem */
    public Compiled compile(String source) {
        return compile(source, ScriptMode.FRAGMENT);
    }

    /** @throws ScriptRejected with the line of the first problem */
    public Compiled compile(String source, ScriptMode mode) {
        if (source == null || source.isBlank()) {
            throw new ScriptRejected("The script is empty.");
        }
        if (source.length() > 100_000) {
            throw new ScriptRejected("The script is longer than 100,000 characters.");
        }
        Set<String> read = new LinkedHashSet<>();
        CompilerConfiguration config = new CompilerConfiguration();
        config.setScriptBaseClass((mode == ScriptMode.FULL ? FullScript.class : FragmentScript.class).getName());
        // @Grab would resolve and download dependencies while compiling, before any check runs.
        config.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
        config.addCompilationCustomizers(
                new Rules(read, mode == ScriptMode.FULL),
                new ASTTransformationCustomizer(Map.of("value", Math.max(1, timeoutMillis), "unit",
                        new org.codehaus.groovy.ast.expr.PropertyExpression(new ClassExpression(ClassHelper.make(java.util.concurrent.TimeUnit.class)), "MILLISECONDS")),
                        TimedInterrupt.class),
                new ASTTransformationCustomizer(ThreadInterrupt.class));
        try (GroovyClassLoader loader = new GroovyClassLoader(ScriptSandbox.class.getClassLoader(), config)) {
            Class<?> type = loader.parseClass(FragmentSyntax.rewrite(source), "endpoint_script.groovy");
            return new Compiled(type, timeoutMillis, read);
        } catch (ScriptRejected e) {
            throw e;
        } catch (CompilationFailedException e) {
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof ScriptRejected r) {
                    throw r;
                }
                cause = cause.getCause();
            }
            throw new ScriptRejected(firstLine(e.getMessage().replace("startup failed:", "").replace("endpoint_script.groovy: ", "").strip()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** The compile-time rules; runs before classes are generated, so a refused script never loads. */
    private static final class Rules extends CompilationCustomizer {

        private final Set<String> read;
        private final boolean full;

        Rules(Set<String> read, boolean full) {
            super(CompilePhase.CANONICALIZATION);
            this.read = read;
            this.full = full;
        }

        @Override
        public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            ModuleNode module = source.getAST();
            if (!module.getImports().isEmpty() || !module.getStarImports().isEmpty() || !module.getStaticImports().isEmpty()
                    || !module.getStaticStarImports().isEmpty()) {
                throw refuse(module, "Imports are not allowed in scripts.");
            }
            if (module.getClasses().size() != 1 || !classNode.isScript()) {
                throw refuse(classNode, "Scripts cannot declare classes.");
            }
            refuseAnnotations(classNode);
            for (MethodNode m : classNode.getMethods()) {
                if (!m.isSynthetic() && !"run".equals(m.getName()) && !"main".equals(m.getName())) {
                    throw refuse(m, "Scripts cannot declare methods; write the logic inline.");
                }
                refuseAnnotations(m);
            }
            if (!classNode.getFields().stream().allMatch(f -> f.isSynthetic() || f.getName().startsWith("$") || f.getName().startsWith("__"))) {
                throw refuse(classNode, "Scripts cannot declare fields.");
            }
            new Checker(source, read, full).visitClass(classNode);
        }

        private static void refuseAnnotations(AnnotatedNode node) {
            // Annotations Groovy adds itself have no position; anything written in the script has one.
            boolean own = node.getAnnotations().stream().anyMatch(a -> a.getLineNumber() > 0);
            if (own) {
                throw refuse(node, "Annotations are not allowed in scripts.");
            }
        }
    }

    private static final class Checker extends ClassCodeVisitorSupport {
        private final SourceUnit source;
        private final Set<String> read;
        private final boolean full;

        Checker(SourceUnit source, Set<String> read, boolean full) {
            this.source = source;
            this.read = read;
            this.full = full;
        }

        private static boolean isVariable(Expression e, String name) {
            return e instanceof VariableExpression v && name.equals(v.getName());
        }

        /** Nodes the interrupt transforms add have no position; script text always has one. */
        private static boolean synthetic(ASTNode node) {
            return node.getLineNumber() < 1;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return source;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            if (synthetic(call)) {
                return;
            }
            if (!(call.getMethod() instanceof ConstantExpression c) || !(c.getValue() instanceof String name)) {
                throw refuse(call, "Method names must be written out, not computed.");
            }
            denyName(call, name);
            if (call.isImplicitThis()) {
                if (!(full ? RECEIVERLESS_FULL : RECEIVERLESS).contains(name)) {
                    throw refuse(call, full ? "'%s' is not available in scripts; use db, sql, raw, orderBy, notFound and badRequest.".formatted(name)
                            : "'%s' is not available in scripts; use sql, raw and orderBy.".formatted(name));
                }
                if ("raw".equals(name) && !literalArgument(call.getArguments())) {
                    throw refuse(call, "raw() only takes a string written in the script, never a value.");
                }
                if ("orderBy".equals(name) && !orderByLiterals(call.getArguments())) {
                    throw refuse(call, "orderBy() takes a map written in the script whose values are strings, such as "
                            + "[newest: 'created_at desc'], then the caller's choice, then a default string.");
                }
                call.getArguments().visit(this); // the receiver is the implicit script itself
                return;
            } else if (call.getObjectExpression() instanceof VariableExpression v && (v.isThisExpression() || v.isSuperExpression())) {
                throw refuse(call, "this and super are not available in scripts.");
            } else if (full && isVariable(call.getObjectExpression(), "db")) {
                if (!DB_METHODS.contains(name) || call.isSafe() || call.isSpreadSafe()) {
                    throw refuse(call, "db offers one, list, value and update.");
                }
                call.getArguments().visit(this);
                return;
            } else if (!ALLOWED_METHODS.contains(name)) {
                throw refuse(call, "Method '%s' is not available in scripts.".formatted(name));
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            if (synthetic(call)) {
                return;
            }
            throw refuse(call, "Static calls are not available in scripts.");
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            if (synthetic(call)) {
                return;
            }
            throw refuse(call, "Creating objects with new is not allowed; use [] and [:] literals.");
        }

        @Override
        public void visitMethodPointerExpression(MethodPointerExpression e) {
            if (synthetic(e)) {
                return;
            }
            throw refuse(e, "Method pointers are not allowed in scripts.");
        }

        @Override
        public void visitAttributeExpression(AttributeExpression e) {
            if (synthetic(e)) {
                return;
            }
            throw refuse(e, "Direct field access (.@) is not allowed in scripts.");
        }

        @Override
        public void visitPropertyExpression(PropertyExpression e) {
            if (synthetic(e)) {
                return;
            }
            if (!(e.getProperty() instanceof ConstantExpression c) || !(c.getValue() instanceof String name)) {
                throw refuse(e, "Property names must be written out, not computed.");
            }
            denyName(e, name);
            if (isVariable(e.getObjectExpression(), "db")) {
                throw refuse(e, "db has no properties; call db.one, db.list, db.value or db.update.");
            }
            if (e.getObjectExpression() instanceof VariableExpression p && "params".equals(p.getName())) {
                read.add(name);
            }
            if (e.getObjectExpression() instanceof VariableExpression v && (v.isThisExpression() || v.isSuperExpression())) {
                throw refuse(e, "this and super are not available in scripts.");
            }
            super.visitPropertyExpression(e);
        }

        @Override
        public void visitVariableExpression(VariableExpression v) {
            if (synthetic(v)) {
                return;
            }
            if (v.isThisExpression() || v.isSuperExpression()) {
                throw refuse(v, "this and super are not available in scripts.");
            }
            denyName(v, v.getName());
            allowType(v, v.getOriginType());
            super.visitVariableExpression(v);
        }

        @Override
        public void visitClassExpression(ClassExpression e) {
            if (synthetic(e)) {
                return;
            }
            allowType(e, e.getType());
            super.visitClassExpression(e);
        }

        @Override
        public void visitCastExpression(CastExpression e) {
            if (synthetic(e)) {
                return;
            }
            allowType(e, e.getType());
            super.visitCastExpression(e);
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression e) {
            if (synthetic(e)) {
                return;
            }
            allowType(e, e.getLeftExpression().getType());
            if (isVariable(e.getLeftExpression(), "db") || isVariable(e.getLeftExpression(), "params")) {
                throw refuse(e, "db and params cannot be declared again.");
            }
            super.visitDeclarationExpression(e);
        }

        @Override
        public void visitClosureExpression(ClosureExpression e) {
            if (synthetic(e)) {
                return;
            }
            if (e.getParameters() != null) {
                for (Parameter p : e.getParameters()) {
                    allowType(e, p.getType());
                }
            }
            super.visitClosureExpression(e);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression e) {
            if (synthetic(e)) {
                return;
            }
            if ("=".equals(e.getOperation().getText())
                    && (isVariable(e.getLeftExpression(), "db") || isVariable(e.getLeftExpression(), "params"))) {
                throw refuse(e, "db and params cannot be assigned.");
            }
            if ("[".equals(e.getOperation().getText())) {
                if (!(e.getRightExpression() instanceof ConstantExpression c)) {
                    throw refuse(e, "Index with a number or name written in the script, such as list[0] or map['key'].");
                }
                if (c.getValue() instanceof String name) {
                    denyName(e, name);
                    if (e.getLeftExpression() instanceof VariableExpression p && "params".equals(p.getName())) {
                        read.add(name);
                    }
                }
            }
            super.visitBinaryExpression(e);
        }

        /** orderBy([key: 'literal', …], anything, 'literal'): only the key may come from the caller. */
        private static boolean orderByLiterals(Expression arguments) {
            if (!(arguments instanceof ArgumentListExpression list) || list.getExpressions().size() != 3) {
                return false;
            }
            if (!(list.getExpression(0) instanceof org.codehaus.groovy.ast.expr.MapExpression map)) {
                return false;
            }
            boolean values = map.getMapEntryExpressions().stream()
                    .allMatch(e -> e.getValueExpression() instanceof ConstantExpression c && c.getValue() instanceof String);
            return values && list.getExpression(2) instanceof ConstantExpression d && d.getValue() instanceof String;
        }

        private static boolean literalArgument(Expression arguments) {
            return arguments instanceof ArgumentListExpression list && list.getExpressions().size() == 1
                    && list.getExpression(0) instanceof ConstantExpression c && c.getValue() instanceof String;
        }

        private static void denyName(ASTNode node, String name) {
            if (DENIED_NAMES.contains(name)) {
                throw refuse(node, "'%s' is not available in scripts.".formatted(name));
            }
        }

        private static void allowType(ASTNode node, ClassNode type) {
            if (type == null) {
                return;
            }
            ClassNode base = type.isArray() ? type.getComponentType() : type;
            ClassNode plain = base.redirect().getPlainNodeReference();
            boolean ok = ALLOWED_TYPES.contains(plain.getName())
                    || plain.getName().equals("endpoint_script") || plain.isScript();
            if (!ok) {
                throw refuse(node, "Type %s is not available in scripts.".formatted(plain.getNameWithoutPackage()));
            }
        }
    }

    private static ScriptRejected refuse(ASTNode node, String message) {
        return new ScriptRejected(node.getLineNumber() > 0 ? "Line %d: %s".formatted(node.getLineNumber(), message) : message);
    }
}
