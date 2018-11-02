package org.janelia.it.fiji.plugins.h5j;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
			String rsdir = "lib/";
			
			if (SystemUtils.IS_OS_WINDOWS) {
				libname += ".dll";
				lib = new File(dir.getPath() + File.separator + libname);
				rsdir += "win64/";
			} else if (SystemUtils.IS_OS_MAC_OSX) {
				libname += ".so";
				lib = new File(dir.getPath() + File.separator + libname);
				rsdir += "macosx/";
			} else if (SystemUtils.IS_OS_LINUX) {
				libname += ".so";
				lib = new File(dir.getPath() + File.separator + libname);
				rsdir += "linux/";
			}
			System.out.println(lib.getPath());
			
			List<String> filenames = new ArrayList<>();
			InputStream in = FFMpegEncoder.class.getResourceAsStream(rsdir);
			BufferedReader br = new BufferedReader(new InputStreamReader(in));
			String resource;
			while ((resource = br.readLine()) != null) {
				filenames.add(resource);
			}
			for (String filename : filenames) {
				File file = new File(dir.getPath() + File.separator + filename);
				if (!file.exists()) {
					if (!dir.exists())
						dir.mkdirs();
					InputStream is = FFMpegEncoder.class.getResourceAsStream(rsdir + filename);
					Files.copy(is, file.getAbsoluteFile().toPath());
					is.close();
				}
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
