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
import ij.process.ImageProcessor;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.FileUtils;

import ch.systemsx.cisd.hdf5.*;

public class H5j_Writer implements PlugInFilter {
    
    private static final String MESSAGE_PREFIX = "HHMI_H5J_Writer: ";
    private static final String EXTENSION = ".h5j";
    public static final String INFO_PROPERTY = "Info";
    
    ImagePlus m_imp;

    boolean isUnix = true;
    
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
		int threads = 0;
		if (options != null) {
			String [] arguments = options.split(" ");
			for (String s : arguments) {
				String [] p = s.split("=");
				if (p == null) continue;
				if (p[0].equals("threads") && p.length >= 2) {
					threads = Integer.parseInt(p[1]);
				}
			}
		}
		//IJ.log("threads: "+threads);

		String os = System.getProperty("os.name");
		if (os.contains("Windows")) 
			isUnix = false;
		
    	saveStackHDF5(directory+filename, m_imp, threads);
    }

	int nearestPowerOfEight(int val) {
		int lb = val >> 3 << 3;
		int ub = (val + 8) >> 3 << 3;

		return (lb == val) ? lb : ub;
	}

	boolean saveStackHDF5(String fileName, ImagePlus img, int threadnum)
    {
        try {

        	FileInfo finfo = img.getFileInfo();
    		if(finfo == null) return false;
        	int[] dims = img.getDimensions();
    		int w = dims[0];
    		int h = dims[1];
    		int nCh = dims[2];
    		int d = dims[3];
    		int nFrame = dims[4];
    		int bdepth = img.getBitDepth();
    		double[] vx_size = {finfo.pixelWidth, finfo.pixelHeight, finfo.pixelDepth};
    		double[] im_size = {w*vx_size[0], h*vx_size[1], d*vx_size[2]};
    		String unit = finfo.unit != null ? finfo.unit : "";
    		
    		ImageStack stack = img.getStack();
    		ImageProcessor[] iplist = new ImageProcessor[d*nCh];
	
    		for ( int c = 0; c < nCh; ++c ) {
    			for ( int z = 0; z < d; ++z ) {
    				iplist[c*d + z] = stack.getProcessor(img.getStackIndex(c+1, z+1, 1));
    			}
    		}
    		
    		final File h5file = new File(fileName);
    		if (h5file.exists())
    			h5file.delete();
    		final IHDF5Writer writer = HDF5Factory.open( h5file );
            writer.object().createGroup("/Channels");

            int scaledHeight = nearestPowerOfEight( h );
            int scaledWidth = nearestPowerOfEight( w );

            ImagePlus tmpimp = NewImage.createImage("slice", scaledWidth, scaledHeight, 1, bdepth, NewImage.FILL_BLACK);

            // Initialize the upper and lower bounds
            int pad_right = ( scaledWidth - w ) ;
            int pad_bottom = ( scaledHeight - h );
			
            writer.float64().setArrayAttr("/", "image_size", im_size);
            writer.float64().setArrayAttr("/", "voxel_size", vx_size);
            writer.string().setAttr("/", "unit", unit);
            
            writer.int64().setAttr("/Channels", "width", w);
            writer.int64().setAttr("/Channels", "height", h);
            writer.int64().setAttr("/Channels", "frames", d);
            writer.int64().setAttr("/Channels", "pad_right", pad_right);
            writer.int64().setAttr("/Channels", "pad_bottom", pad_bottom);
            
            String options = (bdepth == 8 ? "crf=15" : "crf=7") + ":psy-rd=1.0";
            if (threadnum > 0) options += ":pools="+threadnum;
            Path myTempDir = Files.createTempDirectory(null);
            String tdir = myTempDir.toString() + "/";
            
            for ( int c = 0; c < nCh; c++ )
            {
            	IJ.showProgress((double)c / nCh);
                double default_irange = 1.0; // assumes data range is 0-255.0
                List<Double> imin = new ArrayList<>(Collections.nCopies(nCh, 0.0));
        		List<Double> irange2 = new ArrayList<>(Collections.nCopies(nCh, default_irange));

		        String imageseq_path = tdir + "z%05d.tiff";
		        String video_path = tdir + "v.mp4";
		        //IJ.log(imageseq_path);
		        
					if (bdepth == 8) {
						int slicesize = w*h;
						ImageProcessor tmpip = tmpimp.getProcessor();
        				for ( int z = 0; z < d; z++ ) {
        					for ( int y = 0; y < h; y++ ) {
        						for ( int x = 0; x < w; x++ )
        							tmpip.setf(y*scaledWidth+x, iplist[c*d + z].getf(y*w+x));
        					}
        					FileInfo sfinfo = tmpimp.getFileInfo();
        					String path = tdir + String.format("z%05d", z) + ".tiff";
        					BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(path));
        					TiffEncoder enc = new TiffEncoder(sfinfo);
        					enc.write(output);
        					output.close();
        				}
        			} else {
        				int slicesize = w*h;
        				ImageProcessor tmpip = tmpimp.getProcessor();
        				for ( int z = 0; z < d; z++ ) {
        					for ( int y = 0; y < h; y++ ) {
        						for ( int x = 0; x < w; x++ )
        							tmpip.setf(y*scaledWidth+x, iplist[c*d + z].getf(y*w+x) * 16); //scale from glay12 to glay16
        					}
        					FileInfo sfinfo = tmpimp.getFileInfo();
        					String path = tdir + String.format("z%05d", z) + ".tiff";
        					BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(path));
        					TiffEncoder enc = new TiffEncoder(sfinfo);
        					enc.write(output);
        					output.close();
        				}
        			}

				String[] listCommands = {
					IJ.getDirectory("plugins")+"/ffmpeg",
					"-y",
					"-i", imageseq_path,
					"-pix_fmt", (bdepth == 8 ? "yuv444p" : "gray12"),
					"-c:v", "libx265",
					"-preset", "medium",
					"-x265-params", options,
					video_path
				};

				FFMPEGThread ffmpeg = new FFMPEGThread(listCommands);
        		ffmpeg.start();
        		
        		ffmpeg.join();

        		//IJ.log(ffmpeg.getStdOut());
        		//IJ.log(ffmpeg.getStdErr());
        		
				File vdf = new File(video_path);
                byte[] arr = IOUtils.toByteArray(new BufferedInputStream(new FileInputStream(vdf)));
                String dataset_path = "/Channels/Channel_" + c;
                writer.int8().createArray(dataset_path, arr.length);
                writer.int8().writeArray(dataset_path, arr);
            }
            
            IJ.showProgress(1.0);
            writer.file().flush();
            writer.close();

			FileUtils.deleteDirectory(new File(myTempDir.toString()));
            tmpimp.close();

            return true;
        }catch(Exception e){
        	e.printStackTrace();
        }

        return false;
	}

    class FFMPEGThread extends Thread{
        String[] command;
        StringBuffer stdout_sb = new StringBuffer();
        StringBuffer stderr_sb = new StringBuffer();
        FFMPEGThread(String[] command){        
            this.command=command;            
        }
        public void run(){      
			try{          
				String s = null;
                Process process = new ProcessBuilder(command).start();                
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                // read the output from the command                    
                while ((s = stdInput.readLine()) != null)
                    stdout_sb.append(s+"\n");
                stdInput.close();
                // read any errors from the attempted command                
                while ((s = stdError.readLine()) != null)
                    stderr_sb.append(s+"\n");    
            }catch(Exception ex){
                System.out.println(ex.toString());
            }
        }
        public String getStdOut() { return stdout_sb.toString(); }
        public String getStdErr() { return stderr_sb.toString(); }
    }
}
