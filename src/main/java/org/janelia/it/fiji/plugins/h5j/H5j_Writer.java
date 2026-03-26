/*
 * Copyright 2015 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */

package org.janelia.it.fiji.plugins.h5j;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.io.SaveDialog;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;
import org.bytedeco.javacpp.BytePointer;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H265;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;

/**
 * Fiji plugin for HHMI HDF 5 writer. Consumes H.265-compressed data.
 *
 * @author takashi
 */
public class H5j_Writer extends ImagePlus implements PlugInFilter {

    private static final String MESSAGE_PREFIX = "HHMI_H5J_Writer: ";
    private static final String EXTENSION = ".h5j";
    public static final String INFO_PROPERTY = "Info";

    ImagePlus m_imp;

    static void check(int err) {
        if (err < 0) {
            BytePointer e = new BytePointer(512);
            av_strerror(err, e, 512);
            throw new RuntimeException(e.getString().substring(0, (int) BytePointer.strlen(e)) + ":" + err);
        }
    }

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
        saveStackHDF5(directory + filename, m_imp);
    }

    public boolean saveStackFFMpeg(String file_name, ImagePlus img) {
        int[] dims = img.getDimensions();
        int w = dims[0];
        int h = dims[1];
        int nCh = dims[2];
        int d = dims[3];
        int nFrame = dims[4];
        int bdepth = img.getBitDepth();
        ImageStack stack = img.getStack();
        ImageProcessor[] iplist = new ImageProcessor[d * nCh];

        for (int c = 0; c < nCh; ++c) {
            for (int z = 0; z < d; ++z) {
                iplist[c * d + z] = stack.getProcessor(img.getStackIndex(c + 1, z + 1, 1));
            }
        }

        double default_irange = 1.0; // assumes data range is 0-255.0
        if (bdepth > 8)
            IJ.run(img, "Multiply...", "value=16"); // 0-4096, like our microscope images
        List<Double> imin = new ArrayList<>(Collections.nCopies(nCh, 0.0));
        List<Double> irange2 = new ArrayList<>(Collections.nCopies(nCh, default_irange));

        FFMpegEncoder encoder = new FFMpegEncoder(
                file_name,
                w, h, bdepth,
                "libx265",
                AV_CODEC_ID_H265,
                "crf=7:psy-rd=1.0");
        for (int z = 0; z < d; ++z) {
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    int spxid = y * w + x;
                    for (int c = 0; c < 3; ++c) {
                        int ic = c;
                        if (c >= nCh) ic = 0; // single channel volume to gray RGB movie
                        double val = iplist[ic * d + z].getf(spxid);
                        val = (val - imin.get(ic)) * irange2.get(ic); // rescale to range 0-255
                        encoder.setPixelIntensity(x, y, c, (byte) val);
                    }
                }
            }
            encoder.write_frame();
        }

        encoder.close();

        return true;
    }

    int nearestPowerOfEight(int val) {
        int lb = val >> 3 << 3;
        int ub = (val + 8) >> 3 << 3;

        return (lb == val) ? lb : ub;
    }

    boolean saveStackHDF5(String fileName, ImagePlus img) {
        try {
            FileInfo finfo = img.getFileInfo();
            if (finfo == null) return false;
            int[] dims = img.getDimensions();
            int w = dims[0];
            int h = dims[1];
            int nCh = dims[2];
            int d = dims[3];
            int nFrame = dims[4];
            int bdepth = img.getBitDepth();
            double[] vx_size = {finfo.pixelWidth, finfo.pixelHeight, finfo.pixelDepth};
            double[] im_size = {w * vx_size[0], h * vx_size[1], d * vx_size[2]};
            String unit = finfo.unit != null ? finfo.unit : "";

            ImageStack stack = img.getStack();
            ImageProcessor[] iplist = new ImageProcessor[d * nCh];

            for (int c = 0; c < nCh; ++c) {
                for (int z = 0; z < d; ++z) {
                    iplist[c * d + z] = stack.getProcessor(img.getStackIndex(c + 1, z + 1, 1));
                }
            }

            final File h5file = new File(fileName);
            if (h5file.exists())
                h5file.delete();
            final IHDF5Writer writer = HDF5Factory.configure(h5file)
                    .useSimpleDataSpaceForAttributes()
                    .dontUseExtendableDataTypes()
                    .writer();
            writer.object().createGroup("/Channels");

            long scaledHeight = nearestPowerOfEight(h);
            long scaledWidth = nearestPowerOfEight(w);

            // Initialize the upper and lower bounds
            long pad_right = (scaledWidth - w);
            long pad_bottom = (scaledHeight - h);

            writer.float64().setArrayAttr("/", "image_size", im_size);
            writer.float64().setArrayAttr("/", "voxel_size", vx_size);
            writer.string().setAttr("/", "unit", unit);

            writer.int64().setAttr("/Channels", "width", w);
            writer.int64().setAttr("/Channels", "height", h);
            writer.int64().setAttr("/Channels", "frames", d);
            writer.int64().setAttr("/Channels", "pad_right", pad_right);
            writer.int64().setAttr("/Channels", "pad_bottom", pad_bottom);

            String options = (bdepth == 8 ? "crf=15:psy-rd=1.0" : "crf=7:psy-rd=1.0");

            double total_slices = nCh * d;
            long current_slice = 0;
            IJ.showProgress(current_slice / total_slices);
            for (int c = 0; c < nCh; c++) {
                double default_irange = 1.0; // assumes data range is 0-255.0
                List<Double> imin = new ArrayList<>(Collections.nCopies(nCh, 0.0));
                List<Double> irange2 = new ArrayList<>(Collections.nCopies(nCh, default_irange));
                FFMpegEncoder encoder = new FFMpegEncoder(
                        (String) null,
                        (int) scaledWidth, (int) scaledHeight, bdepth,
                        "libx265",
                        AV_CODEC_ID_H265,
                        options);
                if (!encoder.isReady()) {
                    IJ.log("FFMpegEncoder failed to initialize (codec not found - check that JavaCPP native libs are accessible)");
                    return false;
                }
                // If the image needs padding, fill the expanded border regions with black
                if (bdepth == 8) {
                    for (int z = 0; z < d; z++) {
                        for (int y = 0; y < scaledHeight; y++) {
                            for (int x = 0; x < scaledWidth; x++) {
                                // If inside the area with valid data
                                if (x < w && y < h) {
                                    int ic = c;
                                    if (c >= nCh) ic = 0; // single channel volume to gray RGB movie
                                    double val = iplist[ic * d + z].getf(y * w + x);
                                    val = (val - imin.get(ic)) * irange2.get(ic); // rescale to range 0-255
                                    for (int cc = 0; cc < 3; ++cc)
                                        encoder.setPixelIntensity(x, y, cc, (byte) val);
                                } else
                                    for (int cc = 0; cc < 3; ++cc)
                                        encoder.setPixelIntensity(x, y, cc, (byte) 0);
                            }
                        }
                        encoder.write_frame();
                        current_slice++;
                        IJ.showProgress(current_slice / total_slices);
                        if (z == d - 1)
                            encoder.write_frame();
                    }
                } else {
                    for (int z = 0; z < d; z++) {
                        for (int y = 0; y < scaledHeight; y++) {
                            for (int x = 0; x < scaledWidth; x++) {
                                // If inside the area with valid data
                                if (x < w && y < h) {
                                    double val = iplist[c * d + z].getf(y * w + x) * 16;
                                    encoder.setPixelIntensity(x, y, (short) val);
                                } else
                                    encoder.setPixelIntensity(x, y, (short) 0);
                            }
                        }
                        encoder.write_frame();
                        current_slice++;
                        IJ.showProgress(current_slice / total_slices);
                        if (z == d - 1)
                            encoder.write_frame();
                    }
                }

                for (int rem = encoder.encoded_frames(); rem < d + 1; rem++)
                    encoder.encode(null);

                encoder.close();
                byte[] arr = new byte[encoder.buffer_size()];
                encoder.buffer().get(arr);
                String dataset_path = "/Channels/Channel_" + c;
                writer.uint8().createArray(dataset_path, arr.length);
                writer.uint8().writeArray(dataset_path, arr);
                IJ.log("Finished Dataset " + dataset_path + " (" + arr.length + " bytes)");
            }
            writer.file().flush();
            writer.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

}
