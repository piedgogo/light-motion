package dk.dren.lightmotion.core;

import dk.dren.lightmotion.core.snapshot.SnapshotProcessingManager;
import dk.dren.lightmotion.onvif.ONVIFCamera;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.xml.sax.SAXException;

import javax.xml.soap.SOAPException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Read a camera, this means two things:
 * 1: Run a thread that periodically polls the snapshot url to get a snapshot, which is then fed into the snapshot queue.
 * 2: Run a thread that starts the external streamer process and waits for it to quit and if it does, then restarts it.
 */
@Log
@RequiredArgsConstructor
@Getter
public class CameraManager {
    private final LightMotion lightMotion;
    private final CameraConfig cameraConfig;
    private Thread snapshotThread;
    private Thread streamThread;
    private ONVIFCamera onvif;
    private boolean keepRunning = true;
    private String error;
    private Integer framerate;
    private Integer width;
    private Integer height;
    private Process streamProcess;
    private boolean streamProcessRunning;
    private boolean streamProcessKilling;
    private SnapshotProcessingManager snapshotProcessingManager;

    void start() {
        snapshotThread = new Thread(() -> {
            try {
                snapshotProcessingManager = new SnapshotProcessingManager(this);
                interrogateOnvif();
                startStreamThread();
                pollSnapshots();
            } catch (Throwable e) {
                if (keepRunning) {
                    log.log(Level.SEVERE, "Failed in the snapshot thread for " + cameraConfig.getName() + ": ", e);
                    error = "Snapshot thread exited " + e.toString();
                }
            }
        });
        snapshotThread.setName("Polling snapshots from "+cameraConfig.getName());
        snapshotThread.setDaemon(true);
        snapshotThread.start();
    }

    void stop() {
        keepRunning = false;
        snapshotThread.interrupt();
        streamThread.interrupt();
    }

    private void interrogateOnvif() throws SOAPException, SAXException, IOException {
        onvif = new ONVIFCamera(cameraConfig.getAddress(), cameraConfig.getUser(), cameraConfig.getPassword(), cameraConfig.getProfileNumber());

        framerate = cameraConfig.getForceFramerate() != null
                ? cameraConfig.getForceFramerate()
                : onvif.getProfile().getFramerate();

        if (framerate == null) {
            error = "Neither ONVIF nor YAML has a framerate for "+cameraConfig.getName()+" add forceFramerate=... to the camera section in the YAML file";
            log.severe(error);
            throw new RuntimeException(error);
        }

        width = cameraConfig.getForceWidth() != null
                ? cameraConfig.getForceWidth()
                : onvif.getProfile().getWidth();
        if (width == null) {
            error = "Neither ONVIF nor YAML has a width for "+cameraConfig.getName()+" add forceWidth=... to the camera section in the YAML file";
            log.severe(error);
            throw new RuntimeException(error);
        }

        height = cameraConfig.getForceHeight() != null
                ? cameraConfig.getForceHeight()
                : onvif.getProfile().getHeight();
        if (height == null) {
            error = "Neither ONVIF nor YAML has a height for "+cameraConfig.getName()+" add forceHeight=... to the camera section in the YAML file";
            log.severe(error);
            throw new RuntimeException(error);
        }
    }


    private void startStreamThread() {
        snapshotThread.setName("Polling snapshots from "+cameraConfig.getName()+" via "+onvif.getSnapshotUri());
        streamThread = new Thread(() -> {
            try {
                streamer();
            } catch (Throwable e) {
                if (!keepRunning) {
                    log.log(Level.SEVERE, "Failed in the streaming thread for " + cameraConfig.getName() + ": ", e);
                    error = "Streaming thread exited " + e.toString();
                }
            }
        });
        streamThread.setName("Streaming from "+cameraConfig.getName()+" via "+onvif.getStreamUri());
        streamThread.setDaemon(true);
        streamThread.start();
    }

    public static String getTimeStamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
        return sdf.format(new Date());
    }

    public File camDir() {
        return new File(lightMotion.getConfig().getWorkingRoot(), "cam/"+cameraConfig.getName());
    }

    public File workingDir() {
        return new File(camDir(), "work");
    }

    public File videoDir() {
        return new File(camDir(), "video");
    }

    private long getStreamerPid() {
        // TODO: Once Java 9 comes out we finally get Process.getPid() and we can get rid of this bullshit
        try {
            if (streamProcess.getClass().getName().equals("java.lang.UNIXProcess")) {
                Field f = streamProcess.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                return ((Integer)f.get(streamProcess)).longValue();
            } else {
                throw new RuntimeException("Unsupported process class: "+streamProcess.getClass().getName());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final Object KILL_LOCK = new Object();
    private void killStreamer() throws IOException, InterruptedException {
        synchronized (KILL_LOCK) {
            streamProcessKilling = true;
            long streamerPid = getStreamerPid();
            killProcess(streamerPid, "HUB"); // Ask nicely
            if (streamProcessKilling) {
                if (!streamProcess.waitFor(5, TimeUnit.SECONDS)) {
                    killProcess(streamerPid, "KILL");
                }
            }
        }
    }

    private void killProcess(long streamerPid, String signal) throws InterruptedException, IOException {
        ProcessBuilder killer = new ProcessBuilder("kill", "-"+signal, Long.toString(streamerPid));
        killer.inheritIO().start().waitFor();
    }

    /**
     * Keep the openRTSP process running if it crashes.
     */
    private void streamer() throws IOException, InterruptedException {

        while (keepRunning) {

            String timestamp = getTimeStamp();
            FileUtils.forceMkdir(videoDir());

            List<String> cmd = new ArrayList<>();
            cmd.add(lightMotion.getOpenRTSP().getAbsolutePath());
            cmd.add("-v"); // store only video
            cmd.add("-4"); // Store mp4
            cmd.add("-b"); cmd.add("500000"); // Large buffer size
            cmd.add("-f"); cmd.add(framerate.toString()); // Set framerate
            cmd.add("-w"); cmd.add(width.toString()); // Set width
            cmd.add("-h"); cmd.add(height.toString()); // Set width
            cmd.add("-F"); cmd.add(videoDir().getAbsolutePath()+"/"+timestamp); // The output directory + file prefix
            cmd.add("-P"); cmd.add(lightMotion.getConfig().getChunkLength().toString()); // Length of the chunks to record
            cmd.add(onvif.getStreamUri());

            log.info("Running: "+String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(new File(videoDir(), timestamp+".log"));
            pb.redirectOutput(new File("/dev/null"));
            pb.directory(videoDir());

            streamProcess = pb.start();
            streamProcessRunning = true;
            int err = -1;
            try {
                err = streamProcess.waitFor();
                log.info("Exit code from openRTSP was: "+err);
            } finally {
                streamProcessRunning = false;
                streamProcessKilling = false;
            }
            if (err != 0) {
                Thread.sleep(1000); // Don't burn all the CPU in case of an error.
            }
        }

        if (streamProcessRunning) {
            killStreamer();
        }
    }

    /**
     * fetches a snapshot from the camera and stores it in the queue for the main thread to take care of
     */
    private void pollSnapshots() throws InterruptedException, IOException {
        try (CloseableHttpClient hc = HttpClients.createDefault()) {
            while (keepRunning) {

                log.fine("Fetching "+onvif.getSnapshotUri());
                HttpGet req = new HttpGet(onvif.getSnapshotUri());
                try (CloseableHttpResponse response = hc.execute(req)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        log.severe("Got error response "+response.getStatusLine().getStatusCode()+" from "+onvif.getSnapshotUri());
                        Thread.sleep(5000);

                    } else {
                        byte[] imageBytes;
                        try (InputStream content = response.getEntity().getContent()) {
                            imageBytes = IOUtils.toByteArray(content);
                        }

                        String imageName = cameraConfig.getName()+"-"+getTimeStamp();

                        // This might block while waiting for room in the queue, so we do this after closing the http response
                        lightMotion.getSnapshots().add(new CameraSnapshot(snapshotProcessingManager, imageName, imageBytes));
                    }

                } catch (Exception e) {
                    log.warning("Failed while requesting "+onvif.getSnapshotUri()+" "+e);
                }

                Thread.sleep(lightMotion.getConfig().getPollInterval());
            }
        }
    }



    /**
     * Handle a snapshot image in the main motion detection thread.
     *
     * @param imageBytes The snapshot received from the camera.
     */
    public void processSnapshot(byte[] imageBytes) {
        log.info("Processing image");
    }
}
