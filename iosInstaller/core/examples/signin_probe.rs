//! `cargo run -p nuvioz-installer-core --example signin_probe [server ...]`: runs the sign-in path with a fake
//! account and prints the full error. Reaches the anisette servers and Apple, never a real Apple Account.
use nuvioz_installer_core::account;

#[tokio::main]
async fn main() {
    nuvioz_installer_core::init();
    let args: Vec<String> = std::env::args().skip(1).collect();
    let servers: Vec<&str> = if args.is_empty() { account::ANISETTE_SERVERS.to_vec() } else { args.iter().map(String::as_str).collect() };
    let never = |_p: account::TwoFactorPrompt| async { Ok::<_, account::Report>(account::TwoFactorReply::Abort) };
    match account::sign_in("nuvioz-probe-no-such-account@example.com", "not-a-real-password", &servers, never, None).await {
        Ok(_) => println!("signed in (unexpected)"),
        Err(e) => println!("{:?} code={:?}\nsummary: {}\n---\n{}", e.kind, e.code, e.summary, e.detail),
    }
}
