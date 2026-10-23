//! JNI bridge for secret bytes. No Java exception, UDID or store payload is logged.
use crate::pairing::{Failure, Store};
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject, JString, JValue, JValueOwned},
};

pub struct AndroidStore<'a, 'local> {
    pub env: &'a mut JNIEnv<'local>,
    pub object: JObject<'local>,
}

impl<'local> AndroidStore<'_, 'local> {
    fn failure(&mut self) -> Failure {
        let mut code = String::new();
        if let Ok(exception) = self.env.exception_occurred() {
            let _ = self.env.exception_clear();
            if !exception.is_null() {
                if let Ok(message) =
                    self.env
                        .call_method(&exception, "getMessage", "()Ljava/lang/String;", &[])
                {
                    if let Ok(message) = message.l() {
                        if !message.is_null() {
                            if let Ok(text) = self.env.get_string(&JString::from(message)) {
                                code = text.into();
                            }
                        }
                    }
                }
            }
        }
        let _ = self.env.exception_clear();
        match code.as_str() {
            "key_unavailable" => (
                "pairing_store_key",
                "Android Keystore key is unavailable. Existing records were retained; do not reset iPhone trust.",
            ),
            "store_corrupt" | "store_unreadable" => (
                "pairing_store_corrupt",
                "Encrypted store could not be authenticated/read. Existing records were retained; explicit repair is required.",
            ),
            "store_verify_failed" => (
                "pairing_store_verify",
                "Saved record did not read back correctly. Do not continue to installation.",
            ),
            _ => (
                "pairing_store",
                "Private pairing storage operation failed. No secret details are included in this diagnostic.",
            ),
        }
    }
    fn call(
        &mut self,
        method: &str,
        signature: &str,
        args: &[JValue],
    ) -> Result<JValueOwned<'local>, Failure> {
        match self.env.call_method(&self.object, method, signature, args) {
            Ok(value) => Ok(value),
            Err(_) => Err(self.failure()),
        }
    }
    fn device(&mut self, udid: &str) -> Result<JObject<'local>, Failure> {
        match self.env.new_string(udid) {
            Ok(s) => Ok(s.into()),
            Err(_) => Err(self.failure()),
        }
    }
}

impl Store for AndroidStore<'_, '_> {
    fn identity(&mut self, udid: &str) -> Result<(String, String), Failure> {
        let device = self.device(udid)?;
        let result = self
            .call(
                "identity",
                "(Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&device)],
            )?
            .l()
            .map_err(|_| self.failure())?;
        let text: String = self
            .env
            .get_string(&JString::from(result))
            .map_err(|_| ("pairing_store", "Host identity unavailable"))?
            .into();
        let (host, buid) = text
            .split_once('\n')
            .filter(|(h, b)| !h.is_empty() && !b.is_empty())
            .ok_or(("pairing_store", "Invalid persisted host identity"))?;
        Ok((host.to_string(), buid.to_string()))
    }
    fn load(&mut self, udid: &str) -> Result<Option<Vec<u8>>, Failure> {
        let device = self.device(udid)?;
        let result = self
            .call(
                "loadRecord",
                "(Ljava/lang/String;)[B",
                &[JValue::Object(&device)],
            )?
            .l()
            .map_err(|_| self.failure())?;
        if result.is_null() {
            return Ok(None);
        }
        self.env
            .convert_byte_array(JByteArray::from(result))
            .map(Some)
            .map_err(|_| self.failure())
    }
    fn save(&mut self, udid: &str, bytes: &[u8]) -> Result<(), Failure> {
        let device = self.device(udid)?;
        let payload: JObject = self
            .env
            .byte_array_from_slice(bytes)
            .map_err(|_| ("pairing_store", "Record transfer failed"))?
            .into();
        self.call(
            "saveRecord",
            "(Ljava/lang/String;[B)V",
            &[JValue::Object(&device), JValue::Object(&payload)],
        )?;
        Ok(())
    }
    fn reboot(&mut self, udid: &str, action: i32) -> Result<i32, Failure> {
        let device = self.device(udid)?;
        self.call(
            "rebootState",
            "(Ljava/lang/String;I)I",
            &[JValue::Object(&device), JValue::Int(action)],
        )?
        .i()
        .map_err(|_| self.failure())
    }
}
