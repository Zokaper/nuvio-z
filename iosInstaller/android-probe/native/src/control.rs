//! Attempt-scoped cancellation, including cancellation before JNI starts. No USB/protocol operations.
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

#[derive(Default)]
pub struct Cancellation {
    through: AtomicU64,
}

impl Cancellation {
    pub const fn new() -> Self {
        Self {
            through: AtomicU64::new(0),
        }
    }
    // Java allocates strictly increasing positive attempt IDs within this process.
    pub fn cancel(&self, attempt: u64) {
        self.through.fetch_max(attempt, Ordering::AcqRel);
    }

    pub fn is_cancelled(&self, attempt: u64) -> bool {
        attempt <= self.through.load(Ordering::Acquire)
    }

    pub async fn wait(&self, attempt: u64) {
        while !self.is_cancelled(attempt) {
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(start_paused = true)]
    async fn cancellation_before_start_does_not_poison_the_next_attempt() {
        let control = Cancellation::new();
        control.cancel(1);
        control.wait(1).await;
        assert!(!control.is_cancelled(2));
        control.cancel(1); // Late detach from the previous attempt.
        assert!(!control.is_cancelled(2));
    }

    #[tokio::test(start_paused = true)]
    async fn cancellation_interrupts_a_silent_operation_and_allows_reconnect() {
        let control = Cancellation::new();
        let cancel = async {
            tokio::time::sleep(Duration::from_secs(1)).await;
            control.cancel(3);
        };
        let work = async {
            tokio::select! {
                biased;
                _ = control.wait(3) => "cancelled",
                _ = tokio::time::sleep(Duration::from_secs(45)) => "deadline",
            }
        };
        let (_, result) = tokio::join!(cancel, work);
        assert_eq!(result, "cancelled");
        assert!(!control.is_cancelled(4));
    }
}
