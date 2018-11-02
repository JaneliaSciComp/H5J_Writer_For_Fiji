package org.janelia.it.fiji.plugins.h5j;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.io.FileOutputStream;
import java.util.ArrayList;

import com.sun.jna.Pointer;
import com.sun.jna.Memory;
import com.sun.jna.Native;

/**
 * Unit test for simple App.
 */
public class FFMpegEncoderTest 
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public FFMpegEncoderTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
        return new TestSuite( FFMpegEncoderTest.class );
    }

    /**
     * Rigourous Test :-)
     */
    public void testApp()
    {
    	int width = 512;
    	int height = 512;
    	int depth = 256;

    	ArrayList<Pointer> stack = new ArrayList<Pointer>();
        
    	for (int z = 0; z < depth; z++) {
    		Pointer slice = new Memory(width*height * Native.getNativeSize(Byte.TYPE));
    		for (int y = 0; y < height; y++) {
    			for (int x = 0; x < width; x++) {
    				int id = y * width + x;
    				slice.setByte(id, (byte)((x+z) % 255));
    			}
    		}
    		stack.add(slice);
    	}
    	
        FFMpegEncoder enc = new FFMpegEncoder(width, height, 8, "libx265", "crf=17:psy-rd=1.0");
        
        for (int z = 0; z < depth; z++)
        	enc.SendSlice8(stack.get(z));
        
        enc.Encode();

		byte[] arr = enc.GetOutput();
		try {
			FileOutputStream stream = new FileOutputStream("test_output.mp4");
			try {
				stream.write(arr);
			} finally {
				stream.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

        enc.Close();
        assertTrue(true);
    }
}
