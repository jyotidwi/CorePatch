package toolkit.coderstory;


import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;

import com.coderstory.toolkit.BuildConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class CorePatchForQ extends XposedHelper {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        // 允许降级
        Class<?> packageClazz = XposedHelpers.findClass("android.content.pm.PackageParser.Package", loadPackageParam.classLoader);
        hookAllMethods("com.android.server.pm.PackageManagerService", loadPackageParam.classLoader, "checkDowngrade", new XC_MethodHook() {
            public void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (prefs.getBoolean("downgrade", true)) {
                    Object packageInfoLite = methodHookParam.args[0];

                    if (prefs.getBoolean("downgrade", true)) {
                        Field field = packageClazz.getField("mVersionCode");
                        field.setAccessible(true);
                        field.set(packageInfoLite, 0);
                        field = packageClazz.getField("mVersionCodeMajor");
                        field.setAccessible(true);
                        field.set(packageInfoLite, 0);
                    }
                }
            }
        });

        hookAllMethods("android.util.jar.StrictJarVerifier", loadPackageParam.classLoader, "verifyMessageDigest",
                new ReturnConstant(prefs, "authcreak", true));
        hookAllMethods("android.util.jar.StrictJarVerifier", loadPackageParam.classLoader, "verify",
                new ReturnConstant(prefs, "authcreak", true));
        hookAllMethods("java.security.MessageDigest", loadPackageParam.classLoader, "isEqual",
                new ReturnConstant(prefs, "authcreak", true));
        hookAllMethods("com.android.server.pm.PackageManagerServiceUtils", loadPackageParam.classLoader, "verifySignatures",
                new ReturnConstant(prefs, "authcreak", false));

        Class<?> signingDetails = XposedHelpers.findClass("android.content.pm.PackageParser.SigningDetails", loadPackageParam.classLoader);
        Constructor<?> findConstructorExact = XposedHelpers.findConstructorExact(signingDetails, Signature[].class, Integer.TYPE);
        findConstructorExact.setAccessible(true);
        Class<?> packageParserException = XposedHelpers.findClass("android.content.pm.PackageParser.PackageParserException", loadPackageParam.classLoader);
        Field error = XposedHelpers.findField(packageParserException, "error");
        error.setAccessible(true);
        Object[] signingDetailsArgs = new Object[2];
        signingDetailsArgs[0] = new Signature[]{new Signature(SIGNATURE)};
        signingDetailsArgs[1] = 1;
        final Object newInstance = findConstructorExact.newInstance(signingDetailsArgs);
        hookAllMethods("android.util.apk.ApkSignatureVerifier", loadPackageParam.classLoader, "verifyV1Signature", new XC_MethodHook() {
            public void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.afterHookedMethod(methodHookParam);
                if (prefs.getBoolean("authcreak", false)) {
                    Throwable throwable = methodHookParam.getThrowable();
                    if (throwable != null) {
                        Throwable cause = throwable.getCause();
                        if (throwable.getClass() == packageParserException) {
                            if (error.getInt(throwable) == -103) {
                                methodHookParam.setResult(newInstance);
                            }
                        }
                        if (cause != null && cause.getClass() == packageParserException) {
                            if (error.getInt(cause) == -103) {
                                methodHookParam.setResult(newInstance);
                            }
                        }
                    }
                }
            }
        });

        //New package has a different signature
        //处理覆盖安装但签名不一致
        hookAllMethods(signingDetails, "checkCapability", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if ((int) param.args[1] != 4) {
                    param.setResult(trustedDigestCrackEnabled(param.args[0]));
                } else {
                    param.setResult(trustedSigPermEnabled(param.args[0]));
                }
            }
        });
        hookAllMethods(signingDetails, "checkCapabilityRecover",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if ((int) param.args[1] != 4) {
                            param.setResult(trustedDigestCrackEnabled(param.args[0]));
                        } else {
                            param.setResult(trustedSigPermEnabled(param.args[0]));
                        }
                    }
                });

        // if app is system app, allow to use hidden api, even if app not using a system signature
        findAndHookMethod("android.content.pm.ApplicationInfo", loadPackageParam.classLoader, "isPackageWhitelistedForHiddenApis", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (digestCrackEnabled()) {
                    ApplicationInfo info = (ApplicationInfo) param.thisObject;
                    if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                        param.setResult(true);
                    }
                }
            }
        });

        var keySetManagerClass = findClass("com.android.server.pm.KeySetManagerService", loadPackageParam.classLoader);
        if (keySetManagerClass != null) {
            var shouldBypass = new ThreadLocal<Boolean>();
            hookAllMethods(keySetManagerClass, "shouldCheckUpgradeKeySetLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (digestCrackEnabled() &&
                            Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch((o) ->
                                    (/* API 29 */ "preparePackageLI".equals(o.getMethodName()) || /* API 28 */ "installPackageLI".equals(o.getMethodName())))) {
                        shouldBypass.set(true);
                        param.setResult(true);
                    } else {
                        shouldBypass.set(false);
                    }
                }
            });
            hookAllMethods(keySetManagerClass, "checkUpgradeKeySetLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (digestCrackEnabled() && shouldBypass.get()) {
                        param.setResult(true);
                    }
                }
            });
        }
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        hookAllMethods("android.content.pm.PackageParser", null, "getApkSigningVersion", XC_MethodReplacement.returnConstant(1));
        hookAllConstructors("android.util.jar.StrictJarVerifier", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (prefs.getBoolean("enhancedMode", false)) {
                    param.args[3] = Boolean.FALSE;
                }
            }
        });
    }

    boolean digestCrackEnabled() {
        // TODO: check signature for trusted_digestCreak
        return prefs.getBoolean("digestCreak", true) || prefs.getBoolean("trusted_digestCrack", true);
    }

    boolean trustedDigestCrackEnabled(Object signingDetails) {
        return prefs.getBoolean("digestCreak", true) || prefs.getBoolean("trusted_digestCrack", true) && MainHook.isSignatureTrusted(signingDetails);
    }

    boolean trustedSigPermEnabled(Object signingDetails) {
        return prefs.getBoolean("trusted_signaturePermission", true) && MainHook.isSignatureTrusted(signingDetails);
    }
}
