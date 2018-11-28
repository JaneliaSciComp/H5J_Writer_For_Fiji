/*
 * Copyright 2018 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */
package org.janelia.it.fiji.plugins.h5j;

import ij.IJ;
import ij.Macro;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.*;
import ij.gui.*;
import ij.plugin.filter.PlugInFilter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.apache.commons.io.FileUtils;

import ch.systemsx.cisd.hdf5.*;



public class H5j_Writer implements PlugInFilter {

	private static final String MESSAGE_PREFIX = "HHMI_H5J_Writer: ";
	private static final String EXTENSION = ".h5j";
	public static final String INFO_PROPERTY = "Info";

	ImagePlus m_imp;

	boolean isUnix = true;
	int threads = 0;
	ArrayList<Integer> crf = new ArrayList<Integer>();

	public int setup(String arg, ImagePlus imp) {
		this.m_imp = imp;
		return DOES_8G + DOES_16;
	}

	@Override
	public void run(ImageProcessor ip) {
		SaveDialog sd = new SaveDialog("Save as H5J", "", EXTENSION);
		String directory = sd.getDirectory();
		String filename = sd.getFileName();
		if (filename == null)
			return;

		String options = Macro.getOptions();
		if (options != null) {
			String[] arguments = options.split(" ");
			for (String s : arguments) {
				String[] p = s.split("=");
				if (p == null)
					continue;
				if (p[0].equals("threads") && p.length >= 2) {
					threads = Integer.parseInt(p[1]);
				}
				if (p[0].equals("crf") && p.length >= 2) {
					String[] strs = p[1].split(",");
					for (String c : strs) {
						crf.add(Integer.parseInt(c));
					}
				}
			}
		}
		// IJ.log("threads: "+threads);

		String os = System.getProperty("os.name");
		if (os.contains("Windows"))
			isUnix = false;

		saveStackHDF5(directory + filename, m_imp, threads);
	}

	int nearestPowerOfEight(int val) {
		int lb = val >> 3 << 3;
		int ub = (val + 8) >> 3 << 3;

		return (lb == val) ? lb : ub;
	}

	boolean saveStackHDF5(String fileName, ImagePlus img, int threadnum) {
		try {

			FileInfo finfo = img.getFileInfo();
			if (finfo == null)
				return false;
			int[] dims = img.getDimensions();
			final int w = dims[0];
			final int h = dims[1];
			int nCh = dims[2];
			final int d = dims[3];
			int nFrame = dims[4];
			int bdepth = img.getBitDepth();
			double[] vx_size = { finfo.pixelWidth, finfo.pixelHeight, finfo.pixelDepth };
			double[] im_size = { w * vx_size[0], h * vx_size[1], d * vx_size[2] };
			String unit = finfo.unit != null ? finfo.unit : "";

			ImageStack stack = img.getStack();
			ImageProcessor[] iplist = new ImageProcessor[d * nCh];

			for (int c = 0; c < nCh; ++c) {
				for (int z = 0; z < d; ++z) {
					iplist[c * d + z] = stack.getProcessor(img.getStackIndex(c + 1, z + 1, 1));
				}
			}
			
			final int scaledHeight = nearestPowerOfEight(h);
			final int scaledWidth = nearestPowerOfEight(w);
			ArrayList<Pointer> tstack = new ArrayList<Pointer>();
			for (int z = 0; z < d; ++z) {
				Pointer slice = new Memory(scaledWidth * scaledHeight * Native.getNativeSize(bdepth == 8 ? Byte.TYPE : Short.TYPE));
				tstack.add(slice);
			}

			final File h5file = new File(fileName);
			if (h5file.exists())
				h5file.delete();
			final IHDF5Writer writer = HDF5Factory.open(h5file);
			writer.object().createGroup("/Channels");

			// Initialize the upper and lower bounds
			int pad_right = (scaledWidth - w);
			int pad_bottom = (scaledHeight - h);

			writer.float64().setArrayAttr("/", "image_size", im_size);
			writer.float64().setArrayAttr("/", "voxel_size", vx_size);
			writer.string().setAttr("/", "unit", unit);

			writer.int64().setAttr("/Channels", "width", w);
			writer.int64().setAttr("/Channels", "height", h);
			writer.int64().setAttr("/Channels", "frames", d);
			writer.int64().setAttr("/Channels", "pad_right", pad_right);
			writer.int64().setAttr("/Channels", "pad_bottom", pad_bottom);

			Path myTempDir = Files.createTempDirectory(null);
			String tdir = myTempDir.toString() + "/";

			for (int c = 0; c < nCh; c++) {
				IJ.showProgress((double) c / nCh);

				int x265crf = bdepth == 8 ? 15 : 7;
				if (c < crf.size()) {
					int lv = crf.get(c);
					if (lv >= 0 && lv <= 51)
						x265crf = lv;
				}

				String options = "crf=" + x265crf + ":psy-rd=1.0";
				if (threadnum > 0)
					options += ":pools=" + threadnum;

				if (bdepth == 8)  {
					final AtomicInteger ai1 = new AtomicInteger(0);
					final Thread[] threads = newThreadArray();
					final int fc = c;
					for (int ithread = 0; ithread < threads.length; ithread++) {
						threads[ithread] = new Thread() {
							{ setPriority(Thread.NORM_PRIORITY); }
							public void run() {
								for (int z = ai1.getAndIncrement(); z < d; z = ai1.getAndIncrement()) {
									for(int y = 0; y < h; y++) {
										for(int x = 0; x < w; x++) {
											int id = y*w + x;
											long offset = y*scaledWidth + x;
											tstack.get(z).setByte( offset, (byte)(iplist[fc*d + z].get(id)) );
										}
									}				
								}
							}
						};
					}
					startAndJoin(threads);
				} else if (bdepth == 16)  {
					final AtomicInteger ai1 = new AtomicInteger(0);
					final Thread[] threads = newThreadArray();
					final int fc = c;
					for (int ithread = 0; ithread < threads.length; ithread++) {
						threads[ithread] = new Thread() {
							{ setPriority(Thread.NORM_PRIORITY); }
							public void run() {
								for (int z = ai1.getAndIncrement(); z < d; z = ai1.getAndIncrement()) {
									for(int y = 0; y < h; y++) {
										for(int x = 0; x < w; x++) {
											int id = y*w + x;
											long offset = (y*scaledWidth + x) * 2;
											tstack.get(z).setShort( offset, (short)(iplist[fc*d + z].get(id)*16) );
										}
									}				
								}
							}
						};
					}
					startAndJoin(threads);
				}

				FFMpegEncoder ffmpeg = new FFMpegEncoder(scaledWidth, scaledHeight, bdepth, "libx265", options);
				
				for (int z = 0; z < d; ++z) {
					if (bdepth == 8)
						ffmpeg.SendSlice8(tstack.get(z));
					else if (bdepth == 16)
						ffmpeg.SendSlice16(tstack.get(z));
				}
				
				ffmpeg.Encode();
				byte[] arr = ffmpeg.GetOutput();
				String dataset_path = "/Channels/Channel_" + c;
				writer.int8().createArray(dataset_path, arr.length);
				writer.int8().writeArray(dataset_path, arr);
				
				ffmpeg.Close();
			}

			IJ.showProgress(1.0);
			writer.file().flush();
			writer.close();

			FileUtils.deleteDirectory(new File(myTempDir.toString()));

			return true;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return false;
	}

	/**
	 * Create a Thread[] array as large as the number of processors available. From
	 * Stephan Preibisch's Multithreading.java class. See:
	 * http://repo.or.cz/w/trakem2.git?a=blob;f=mpi/fruitfly/general/MultiThreading.java;hb=HEAD
	 */
	private Thread[] newThreadArray() {
		int n_cpus = Runtime.getRuntime().availableProcessors();
		if (n_cpus > threads && threads > 0)
			n_cpus = threads;
		if (n_cpus <= 0)
			n_cpus = 1;
		return new Thread[n_cpus];
	}

	/**
	 * Start all given threads and wait on each of them until all are done. From
	 * Stephan Preibisch's Multithreading.java class. See:
	 * http://repo.or.cz/w/trakem2.git?a=blob;f=mpi/fruitfly/general/MultiThreading.java;hb=HEAD
	 */
	public static void startAndJoin(Thread[] threads) {
		for (int ithread = 0; ithread < threads.length; ++ithread) {
			threads[ithread].setPriority(Thread.NORM_PRIORITY);
			threads[ithread].start();
		}

		try {
			for (int ithread = 0; ithread < threads.length; ++ithread)
				threads[ithread].join();
		} catch (InterruptedException ie) {
			throw new RuntimeException(ie);
		}
	}

}