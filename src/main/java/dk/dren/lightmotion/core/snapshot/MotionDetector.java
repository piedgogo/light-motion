package dk.dren.lightmotion.core.snapshot;

import dk.dren.lightmotion.core.CameraManager;
import dk.dren.lightmotion.core.events.LightMotionEvent;
import dk.dren.lightmotion.core.events.LightMotionEventType;
import lombok.extern.java.Log;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Detects motion in the image
 */
@Log
public class MotionDetector implements SnapshotProcessor {
    private static int y0;
    private static int debugImageCount =0;
    final private SnapshotProcessingManager manager;
    private FixedPointPixels average;
    int quietCount = 0;
    private boolean quiet = true;
    private final File averageFile;
    private final File debugDir;
    private int threshold;

    public MotionDetector(SnapshotProcessingManager manager) {
        this.manager = manager;
        averageFile = new File(manager.getWorkingDir(), "average.png");
        debugDir = System.getProperty("debug.dir", "").isEmpty() ? null : new File(System.getProperty("debug.dir"));
        threshold = 10;
    }

    @Override
    public LightMotionEvent process(FixedPointPixels image) {

        int imagePixelCount = image.getWidth() * image.getHeight();
        log.fine("Got image: "+image.getWidth()+"x"+image.getHeight()+" pixels: "+imagePixelCount+" sub-pixels: "+image.getPixels().length);

        if (average == null)  {
            average = image;

        } else if (average.getPixels().length != image.getPixels().length){
            // Note: I don't know if this is a reasonable thing to happen, if it is then this code should learn how to handle it rather than throw an exception
            throw new RuntimeException("The number of pixels of the new image "+image.getPixels().length+" is different from the previously seen "+average.getPixels().length);

        } else {

            long t0 = System.nanoTime();
//            long diff = average.diffBucketUpdate(image, 4);
            MotionDetectionResult diff = average.motionDetect(image, 4, image.getWidth() / 16, image.getHeight() / 16);
            if (debugDir != null) {
                storeDebug(debugDir, average, image, diff, threshold);
            }

            long t1 = System.nanoTime();
            log.fine(manager.getCameraName()+": diff time: "+(t1-t0)+" diff="+diff);

            if (diff.getMaxDiff() > threshold)  {
                log.info("Detected motion at "+diff.getMaxDiffX()+","+diff.getMaxDiffY()+ " = "+diff.getMaxDiff());
                quiet = false;
                quietCount = 0;
                return new LightMotionEvent(LightMotionEventType.MOTION, manager.getCameraName(), "Detected motion ("+diff.getMaxDiff()+")");
            } else {
                if (!quiet && quietCount++ > 10) {
                    quiet = true;
                    return new LightMotionEvent(LightMotionEventType.QUIET, manager.getCameraName(), "No motion detected");
                }
            }

            try {
                average.write(averageFile);
            } catch (Exception e) {
                log.log(Level.SEVERE, "Failed to write average to "+averageFile, e);
            }
        }

        return null;
    }

    private static void storeDebug(File debugDir, FixedPointPixels average, FixedPointPixels image, MotionDetectionResult diff, int threshold) {
        BufferedImage debug = new BufferedImage(average.getWidth()*2, average.getHeight()*2, BufferedImage.TYPE_3BYTE_BGR);

        BufferedImage ai = average.toBufferedImage();
        BufferedImage ii = image.toBufferedImage();
        BufferedImage di = diff.getDiffImage().toBufferedImageWithGradient(threshold);

        Graphics graphics = debug.getGraphics();
        graphics.drawImage(ai, 0,               0,                average.getWidth(), average.getHeight(), null);
        graphics.drawImage(ii, average.getWidth(), 0,                average.getWidth(), average.getHeight(), null);
        graphics.drawImage(di, 0,               average.getHeight(), average.getWidth(), average.getHeight(), null);
        graphics.drawImage(ii, average.getWidth(), average.getHeight(),  average.getWidth(), average.getHeight(), null);
        if (diff.getMaxDiff() >= 0) {
            int xscale = average.getWidth() / diff.getDiffImage().getWidth();
            int yscale = average.getHeight() / diff.getDiffImage().getHeight();
            int x0 = average.getWidth() + xscale * diff.getMaxDiffX();
            int y0 = average.getHeight() + yscale * diff.getMaxDiffY();
            graphics.setColor(new Color(0xff, 0x00, 0x00));
            graphics.drawLine(x0, y0, x0+xscale-1, y0+yscale-1);
            graphics.drawLine(x0+xscale-1, y0, x0, y0+yscale-1);

            graphics.setColor(new Color(0x00, 0xff, 0x00));
            graphics.drawLine(x0, y0, x0+xscale-1, y0);
            graphics.drawLine(x0+xscale-1, y0+yscale-1, x0+xscale-1, y0);
            graphics.drawLine(x0+xscale-1, y0+yscale-1, x0, y0+yscale-1);
            graphics.drawLine(x0, y0, x0, y0+yscale-1);
        }

        graphics.dispose();

        File debugFile = new File(debugDir, "debug-" + debugOutputName() + ".png");
        try {
            ImageIO.write(debug, "png", debugFile);
        } catch (IOException e) {
            throw new RuntimeException("Fail!");
        }
    }

    private static String debugOutputName() {
        String countString = Integer.toHexString(debugImageCount++);
        while (countString.length() < 4) {
            countString = "0"+countString;
        }
        return CameraManager.getTimeStamp() + "-" + countString;
    }
}
