package org.janelia.it.fiji.plugins.h5j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.apache.commons.lang3.SystemUtils;

public class FFMpegEncoder 
{
	static File cacheDir = null;
	static String libpath = null;
	
	static { load(); }
	
	private static void load()
	{
		try {
			String dirpath = getCacheDir().getAbsolutePath() + File.separator + "FFMpegEncoder-1.0.0" + File.separator;
			File dir = new File(dirpath);
			File lib = null;
			String libname = "libFFMpegEncoder";
			String rsdir = "org/janelia/it/fiji/plugins/h5j/lib/";
			
			if (SystemUtils.IS_OS_WINDOWS) {
				libname += ".dll";
				lib = new File(dir.getPath() + File.separator + libname);
				rsdir += "win64/";
			} else if (SystemUtils.IS_OS_MAC_OSX) {
				libname += ".dylib";
				lib = new File(dir.getPath() + File.separator + libname);
				rsdir += "macosx/";
			} else if (SystemUtils.IS_OS_LINUX) {
				libname += ".so";
				lib = new File(dir.getPath() + File.separator + libname);
				rsdir += "linux/";
			}
			//System.out.println(lib.getPath());
			
			final File jarFile = new File(FFMpegEncoder.class.getProtectionDomain().getCodeSource().getLocation().getPath());

			if(jarFile.isFile()) {  // Run with JAR file
			    final JarFile jar = new JarFile(jarFile);
			    final Enumeration<JarEntry> entries = jar.entries(); //gives ALL entries in jar
			    while(entries.hasMoreElements()) {
			    	JarEntry entry = entries.nextElement();
			    	final String name = entry.getName();
			        if (name.startsWith(rsdir) && !name.endsWith("/")) { //filter according to the path
			        	String[] sp = name.split("/");
						String basename = sp[sp.length-1];
			        	File file = new File(dir.getPath() + File.separator + basename);
						if (!file.exists()) {
							File lockFile = new File(dirpath, ".lock");
							FileChannel lockChannel = null;
							FileLock lock = null;
							ReentrantLock threadLock = null;
							try {
								if (!dir.exists())
									dir.mkdirs();
								threadLock = new ReentrantLock();
								threadLock.lock();
								lockChannel = new FileOutputStream(lockFile).getChannel();
								lock = lockChannel.lock();
								InputStream is = jar.getInputStream(entry);
								Files.copy(is, file.getAbsoluteFile().toPath());
								is.close();
							} catch (IOException | RuntimeException e) {
								System.err.println("Could not extract resource file" + basename + ": " + e);
							} finally {
								if (lock != null)
									lock.release();
								if (lockChannel != null)
									lockChannel.close();
								if (threadLock != null)
									threadLock.unlock();
							}
						}
						
						//create symbolic links
						ArrayList<String> linklist = new ArrayList<String>();
						if (SystemUtils.IS_OS_MAC_OSX) {
							String[] strs = basename.split("\\.");
							if (strs.length > 2 && strs[strs.length-1].equals("dylib")) {
								String str = strs[0];
								for (int i = 1; i < strs.length-1; i++) {
									linklist.add(str+".dylib");
									str += "."+strs[i];
								}
							}
						}else if (SystemUtils.IS_OS_LINUX) {
							if (!basename.endsWith(".so")) {
								String[] strs = basename.split("\\.so\\.");
								if (strs.length > 1) {
									String linkname = strs[0]+".so";
									String[] strs2 = strs[1].split("\\.");
									for (int i = 0; i < strs2.length; i++) {
										linklist.add(linkname);
										linkname += "."+strs2[i];
									}
								}
							}
						}
						for (int i = 0; i < linklist.size(); i++) {
							File linkfile = new File(dir.getPath() + File.separator + linklist.get(i));
							Path path = linkfile.toPath(), targetpath = Paths.get(file.getAbsolutePath());
							if (!linkfile.exists() || !Files.isSymbolicLink(path)
									|| !Files.readSymbolicLink(path).equals(targetpath)) {
								File lockFile = new File(dirpath, ".lock");
								FileChannel lockChannel = null;
								FileLock lock = null;
								ReentrantLock threadLock = null;
								try {
									threadLock = new ReentrantLock();
									threadLock.lock();
									lockChannel = new FileOutputStream(lockFile).getChannel();
									lock = lockChannel.lock();
									if (!linkfile.exists() || !Files.isSymbolicLink(path)
											|| !Files.readSymbolicLink(path).equals(targetpath)) {
										try {
											linkfile.getParentFile().mkdirs();
											Files.createSymbolicLink(path, targetpath);
										} catch (java.nio.file.FileAlreadyExistsException e) {
											file.delete();
											Files.createSymbolicLink(path, targetpath);
										}
									}
								} catch (IOException | RuntimeException e) {
									System.err.println("Could not create symbolic link " + basename + ": " + e);
								} finally {
									if (lock != null)
										lock.release();
									if (lockChannel != null)
										lockChannel.close();
									if (threadLock != null)
										threadLock.unlock();
								}
							}
						}
						// System.out.println(basename + " : " + name);
			        }
			    }
			    jar.close();
			}
			
			String libpaths = System.getProperty("java.library.path");
			libpaths = libpaths + ";" + dir.getAbsolutePath();
			System.setProperty("java.library.path", libpaths);
			
			libpath = lib.getAbsolutePath();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static File getCacheDir() throws IOException {
		if (cacheDir == null) {
			String[] dirNames = { System.getProperty("org.bytedeco.javacpp.cachedir"),
					System.getProperty("user.home") + "/.javacpp/cache/",
					System.getProperty("java.io.tmpdir") + "/.javacpp-" + System.getProperty("user.name") + "/cache/" };
			for (String dirName : dirNames) {
				if (dirName != null) {
					try {
						File f = new File(dirName);
						if (f.exists() || f.mkdirs()) {
							cacheDir = f;
							break;
						}
					} catch (SecurityException e) {
						// No access, try the next option.
					}
				}
			}
		}
		if (cacheDir == null) {
			throw new IOException(
					"Could not create the cache: Set the \"org.bytedeco.javacpp.cachedir\" system property.");
		}
		return cacheDir;
	}

	public interface CLibrary extends Library {

		CLibrary INSTANCE = (CLibrary) Native.loadLibrary(libpath, CLibrary.class);

		Pointer FFMpegEncoder_ctor(int width, int height, int bdepth, String codec_name, String options);

		void FFMpegEncoder_SendSlice8(Pointer self, Pointer slice);
		
		void FFMpegEncoder_SendSlice16(Pointer self, Pointer slice);
		
		void FFMpegEncoder_Encode(Pointer self);
		
		int FFMpegEncoder_GetOutputSize(Pointer self);
		
		Pointer FFMpegEncoder_GetOutput(Pointer self);
		
		void FFMpegEncoder_dtor(Pointer self);
	}
	
	private Pointer self = null;

    public FFMpegEncoder(int width, int height, int bdepth, String codec_name, String options)
    {
    	self = CLibrary.INSTANCE.FFMpegEncoder_ctor(width, height, bdepth, codec_name, options);
    }
    
    public void SendSlice8(Pointer slice)
    {
    	CLibrary.INSTANCE.FFMpegEncoder_SendSlice8(self, slice);
    }
    
    public void SendSlice16(Pointer slice)
    {
    	CLibrary.INSTANCE.FFMpegEncoder_SendSlice16(self, slice);
    }
    
    public void Encode()
    {
    	CLibrary.INSTANCE.FFMpegEncoder_Encode(self);
    }
    
    public int GetOutputSize()
    {
    	return CLibrary.INSTANCE.FFMpegEncoder_GetOutputSize(self);
    }
    
    public byte[] GetOutput()
    {
    	Pointer p = CLibrary.INSTANCE.FFMpegEncoder_GetOutput(self);
    	return p.getByteArray(0, CLibrary.INSTANCE.FFMpegEncoder_GetOutputSize(self));
    }
    
    public void Close()
    {
    	CLibrary.INSTANCE.FFMpegEncoder_dtor(self);
    }
}
