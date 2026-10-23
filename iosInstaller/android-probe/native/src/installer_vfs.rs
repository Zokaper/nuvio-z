//! Use the app's private cache for SDK temporary files, without mutating process environment.
use isideload_vfs::{
    OpenOptionsConfig, Vfs, VfsFile, VfsMetadata, VfsPermissions, native::NativeVfs,
};
use std::{
    io,
    path::{Path, PathBuf},
};
struct AndroidVfs(PathBuf);
macro_rules! native {($name:ident($($arg:ident:$ty:ty),*)->$ret:ty)=>{
    fn $name(&self,$($arg:$ty),*)->$ret{NativeVfs.$name($($arg),*)}
};}
impl Vfs for AndroidVfs {
    native!(open_file(path: &Path, options: &OpenOptionsConfig) -> io::Result<Box<dyn VfsFile>>);
    native!(read(path: &Path) -> io::Result<Vec<u8>>);
    native!(write(path: &Path, contents: &[u8]) -> io::Result<()>);
    native!(copy(from: &Path, to: &Path) -> io::Result<u64>);
    native!(rename(from: &Path, to: &Path) -> io::Result<()>);
    native!(remove_file(path: &Path) -> io::Result<()>);
    native!(remove_dir(path: &Path) -> io::Result<()>);
    native!(remove_dir_all(path: &Path) -> io::Result<()>);
    native!(create_dir(path: &Path) -> io::Result<()>);
    native!(create_dir_all(path: &Path) -> io::Result<()>);
    native!(read_link(path: &Path) -> io::Result<PathBuf>);
    native!(set_permissions(path: &Path, perms: Box<dyn VfsPermissions>) -> io::Result<()>);
    native!(metadata(path: &Path) -> io::Result<Box<dyn VfsMetadata>>);
    native!(symlink_metadata(path: &Path) -> io::Result<Box<dyn VfsMetadata>>);
    native!(read_dir(path: &Path) -> io::Result<Vec<PathBuf>>);
    native!(symlink(target: &Path, link: &Path) -> io::Result<()>);
    fn temp_dir(&self) -> PathBuf {
        self.0.clone()
    }
}
pub fn configure(cache: PathBuf) {
    isideload_vfs::set_vfs(Box::new(AndroidVfs(cache)));
}
