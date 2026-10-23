//! JVM/class-loader bootstrap required by reqwest's Android system certificate verifier.
use jni22::{
    EnvUnowned,
    errors::ThrowRuntimeExAndDefault,
    objects::{JClass, JObject},
};

#[jni22::jni_mangle("com.nuvio.usbprobe.NativeUsbProbe")]
pub fn initialize<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) {
    unowned_env
        .with_env(|env| rustls_platform_verifier::android::init_with_env(env, context))
        .resolve::<ThrowRuntimeExAndDefault>();
}
