/**
 * Operit Java Bridge Tester
 *
 * Focused test suite for the new Java/Kotlin bridge runtime:
 * - Java package-chain sugar / Java.use / Java.importClass / Kotlin
 * - Proxy-based class and instance calls
 * - Java.package and package-chain access
 * - NativeInterface.java* low-level bridge
 * - Restricted-class rejection
 */
function assertTrue(condition, message) {
    if (!condition) {
        throw new Error(message);
    }
}
function assertEq(actual, expected, message) {
    if (actual !== expected) {
        throw new Error(`${message} | expected=${String(expected)} actual=${String(actual)}`);
    }
}
function parseNativeResult(raw) {
    const parsed = JSON.parse(raw);
    assertTrue(parsed && typeof parsed === "object", "native result must be object");
    return parsed;
}
async function runCase(definition, params) {
    const started = Date.now();
    try {
        const detail = await definition.handler(params);
        return {
            name: definition.name,
            ok: true,
            durationMs: Date.now() - started,
            detail
        };
    }
    catch (error) {
        return {
            name: definition.name,
            ok: false,
            durationMs: Date.now() - started,
            error: String(error)
        };
    }
}
function caseBridgeExposed() {
    assertTrue(typeof Java === "object", "Java global must exist");
    assertTrue(typeof Kotlin === "object", "Kotlin global must exist");
    assertTrue(Java.classExists("java.lang.StringBuilder"), "StringBuilder should exist");
    assertTrue(!Java.classExists("java.lang.Thread"), "Thread must be blocked");
    const pkgProbe = Java.java && Java.java.lang;
    assertTrue(!!pkgProbe, "Java package-chain proxy should be available");
    return {
        javaExposed: true,
        kotlinExposed: true
    };
}
function caseProxyStaticAndInstance() {
    const Integer = Java.java.lang.Integer;
    const StringBuilderA = Java.use("java.lang.StringBuilder");
    const StringBuilderB = Java.importClass("java.lang.StringBuilder");
    const StringBuilderK = Kotlin.type("java.lang.StringBuilder");
    const maxValue = Integer.MAX_VALUE;
    const parsed = Integer.parseInt("123");
    const parsedByApi = Java.callStatic("java.lang.Integer", "parseInt", "7");
    assertEq(maxValue, 2147483647, "Integer.MAX_VALUE mismatch");
    assertEq(parsed, 123, "Integer.parseInt mismatch");
    assertEq(parsedByApi, 7, "Java.callStatic parseInt mismatch");
    const sbA = new StringBuilderA();
    const sbB = StringBuilderB();
    const sbK = new StringBuilderK();
    sbA.append("A");
    sbA.append("B");
    sbB.append("C");
    sbK.append("K");
    assertEq(sbA.toString(), "AB", "sbA content mismatch");
    assertEq(sbB.toString(), "C", "sbB content mismatch");
    assertEq(sbK.toString(), "K", "sbK content mismatch");
    assertEq(sbA.length(), 2, "sbA length mismatch");
    return {
        integerMax: maxValue,
        parseInt: parsed
    };
}
function casePackageAccess() {
    const utilPkg = Java.package("java.util");
    const ArrayList = utilPkg.ArrayList;
    const list = new ArrayList();
    list.add("x");
    list.add("y");
    assertEq(list.size(), 2, "ArrayList size mismatch");
    return {
        size: list.size()
    };
}
function caseRestrictedBoundary() {
    const blocked = [
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.io.File",
        "android.content.Context",
        "com.ai.assistance.operit.core.application.ActivityLifecycleManager"
    ];
    for (const className of blocked) {
        assertTrue(!Java.classExists(className), `${className} must be blocked`);
    }
    return { blocked };
}
function caseNativeLowLevel() {
    const raw = NativeInterface.javaCallStatic("java.lang.Integer", "parseInt", JSON.stringify(["42"]));
    const parsed = parseNativeResult(raw);
    assertTrue(parsed.success === true, "native javaCallStatic should succeed");
    assertEq(parsed.data, 42, "native javaCallStatic data mismatch");
    return {
        raw,
        parsed
    };
}
const BRIDGE_CASES = [
    { name: "bridge_exposed", handler: caseBridgeExposed },
    { name: "proxy_static_and_instance", handler: caseProxyStaticAndInstance },
    { name: "package_access", handler: casePackageAccess },
    { name: "native_low_level", handler: caseNativeLowLevel },
    { name: "restricted_boundary", handler: caseRestrictedBoundary }
];
async function bridgeMain(params = {}) {
    const startedAt = new Date().toISOString();
    const startedMs = Date.now();
    const requestedName = String(params.caseName || "").trim();
    const selectedCases = requestedName
        ? BRIDGE_CASES.filter(item => item.name === requestedName)
        : BRIDGE_CASES;
    if (selectedCases.length === 0) {
        complete({
            success: false,
            error: `unknown caseName: ${requestedName}`,
            availableCases: BRIDGE_CASES.map(item => item.name)
        });
        return;
    }
    const results = [];
    for (const definition of selectedCases) {
        if (params.verbose) {
            console.log(`[bridge-test] running ${definition.name}`);
        }
        const result = await runCase(definition, params);
        results.push(result);
        if (params.verbose) {
            console.log(`[bridge-test] ${definition.name} => ${result.ok ? "PASS" : "FAIL"} (${result.durationMs}ms)`);
            if (!result.ok) {
                console.error(`[bridge-test] ${definition.name} error: ${result.error}`);
            }
        }
    }
    const passed = results.filter(item => item.ok).length;
    const suiteResult = {
        startedAt,
        durationMs: Date.now() - startedMs,
        passed,
        total: results.length,
        allPassed: passed === results.length,
        results
    };
    complete(suiteResult);
}
exports.main = bridgeMain;
exports.runCase = bridgeMain;
