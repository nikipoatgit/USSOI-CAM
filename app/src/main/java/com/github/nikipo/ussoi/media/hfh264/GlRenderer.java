package com.github.nikipo.ussoi.media.hfh264;

import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.github.nikipo.ussoi.media.utility.SurfaceMode;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class GlRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "GlRenderer";

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglHqSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface eglLqSurface = EGL14.EGL_NO_SURFACE;

    private SurfaceTexture cameraTexture;
    private Surface cameraSurface;
    private int textureId = -1;
    private int programId = -1;

    private final HandlerThread glThread;
    private final Handler glHandler;
    private final AtomicInteger pendingFrames = new AtomicInteger(0);

    private final int width, height;
    private final SurfaceMode surfaceMode;
    private volatile boolean renderLqEnabled = true;
    private static final int EGL_RECORDABLE_ANDROID_FALLBACK = 0x3142;
    private volatile long recordingStartTimeNs = -1;

    // --- PROFILING VARIABLES PLACE HERE AT CLASS LEVEL ---
    private int renderCount = 0;
    private long renderStart = 0;
    private long totalLoopTime = 0;
    private long totalTexUpdateTime = 0;
    private long totalHqDrawTime = 0;
    private long totalLqDrawTime = 0;
    private int profileFrameCount = 0;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTextureCoord = aTextureCoord.xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private static final float[] VERTICES = {
            -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 0.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 0.0f, 1.0f,
            1.0f,  1.0f, 0.0f, 1.0f, 1.0f
    };
    private final FloatBuffer vertexBuffer;

    private volatile int skipCount;
    private int lqSkipCounter;

    public GlRenderer(int width, int height, SurfaceMode surfaceMode) {
        this.width = width;
        this.height = height;
        this.surfaceMode = surfaceMode;
        skipCount = 0;
        this.recordingStartTimeNs = -1;

        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(VERTICES);
        vertexBuffer.position(0);

        glThread = new HandlerThread("GlRendererThread");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
    }

    public void init(Surface hqSurface, Surface lqSurface, int skipCount) {
        this.skipCount = skipCount;
        this.lqSkipCounter = 0;
        glHandler.post(() -> {
            EGLConfig config = initEGL();
            Surface targetHq = (surfaceMode == SurfaceMode.LQ_ONLY) ? null : hqSurface;
            initEGLSurfaces(config, targetHq, lqSurface);
            initGLSetup();
            renderStart = System.currentTimeMillis(); // Init timer start
        });
    }

    private EGLConfig initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        int recordableToken = (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                ? EGLExt.EGL_RECORDABLE_ANDROID
                : EGL_RECORDABLE_ANDROID_FALLBACK;

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                recordableToken, 1,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);

        return configs[0];
    }

    private void initEGLSurfaces(EGLConfig config, Surface hqSurface, Surface lqSurface) {
        int[] surfaceAttribs = { EGL14.EGL_NONE };

        if (hqSurface != null) {
            eglHqSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, hqSurface, surfaceAttribs, 0);
        }
        if (lqSurface != null) {
            eglLqSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, lqSurface, surfaceAttribs, 0);
        }
    }

    private void initGLSetup() {
        EGLSurface defaultSurface = eglHqSurface != EGL14.EGL_NO_SURFACE ? eglHqSurface : eglLqSurface;
        if (defaultSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "No valid EGL Surfaces available to bind contexts.");
            return;
        }
        EGL14.eglMakeCurrent(eglDisplay, defaultSurface, defaultSurface, eglContext);

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        cameraTexture = new SurfaceTexture(textureId);
        cameraTexture.setDefaultBufferSize(width, height);
        cameraTexture.setOnFrameAvailableListener(this, glHandler);

        synchronized (this) {
            cameraSurface = new Surface(cameraTexture);
            this.notifyAll();
        }
    }

    public synchronized Surface getCameraSurface() {
        while (cameraSurface == null) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return cameraSurface;
    }

    public void setLqEnabled(boolean enabled) {
        this.renderLqEnabled = enabled;
    }

    private int callbackCount;
    private long callbackStart;

    @Override
    public void onFrameAvailable(
            SurfaceTexture surfaceTexture) {

        callbackCount++;

        long now = System.currentTimeMillis();

        if (now - callbackStart >= 1000) {
            Log.d(TAG,
                    "FrameAvailable FPS = "
                            + callbackCount);

            callbackCount = 0;
            callbackStart = now;
        }

        if (pendingFrames.get() < 2) {
            pendingFrames.incrementAndGet();
            glHandler.post(this::renderFrame);
        }
    }

    private void renderFrame() {
        long loopStart = System.nanoTime();
        renderCount++;

        long now = System.currentTimeMillis();
        if (now - renderStart >= 1000) {
            if (profileFrameCount > 0) {
                double avgLoop = (totalLoopTime / (double) profileFrameCount) / 1000000.0;
                double avgTex = (totalTexUpdateTime / (double) profileFrameCount) / 1000000.0;
                double avgHq = (totalHqDrawTime / (double) profileFrameCount) / 1000000.0;
                double avgLq = (totalLqDrawTime / (double) profileFrameCount) / 1000000.0;

                Log.d(TAG, String.format(
                        "Render FPS = %d | Avg Times (ms) -> Loop: %.2f | TexUpdate: %.2f | HQ Draw: %.2f | LQ Draw: %.2f",
                        renderCount, avgLoop, avgTex, avgHq, avgLq
                ));
            } else {
                Log.d(TAG, "Render FPS = " + renderCount);
            }

            renderCount = 0;
            renderStart = now;
            totalLoopTime = 0;
            totalTexUpdateTime = 0;
            totalHqDrawTime = 0;
            totalLqDrawTime = 0;
            profileFrameCount = 0;
        }

        if (eglDisplay == EGL14.EGL_NO_DISPLAY || cameraTexture == null) {
            totalLoopTime += (System.nanoTime() - loopStart);
            profileFrameCount++;
            return;
        }

        try {
            long texStart = System.nanoTime();
            cameraTexture.updateTexImage();
            totalTexUpdateTime += (System.nanoTime() - texStart);
        } catch (Exception e) {
            Log.e(TAG, "updateTexImage failed", e);
            pendingFrames.decrementAndGet();
            totalLoopTime += (System.nanoTime() - loopStart);
            profileFrameCount++;
            return;
        }
        pendingFrames.decrementAndGet();

        if (eglHqSurface != EGL14.EGL_NO_SURFACE) {
            long hqStart = System.nanoTime();
            drawToSurface(eglHqSurface);
            totalHqDrawTime += (System.nanoTime() - hqStart);
        }

        if (renderLqEnabled && eglLqSurface != EGL14.EGL_NO_SURFACE) {
            long lqStart = System.nanoTime();
            if (skipCount <= 0) {
                drawToSurface(eglLqSurface);
            } else {
                lqSkipCounter++;
                if (lqSkipCounter >= skipCount) {
                    drawToSurface(eglLqSurface);
                    lqSkipCounter = 0;
                }
            }
            totalLqDrawTime += (System.nanoTime() - lqStart);
        }

        totalLoopTime += (System.nanoTime() - loopStart);
        profileFrameCount++;
    }

    private void drawToSurface(EGLSurface surface) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext);

        GLES20.glViewport(0, 0, width, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(programId);

        vertexBuffer.position(0);
        int ph = GLES20.glGetAttribLocation(programId, "aPosition");
        GLES20.glEnableVertexAttribArray(ph);
        GLES20.glVertexAttribPointer(ph, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer);

        vertexBuffer.position(3);
        int tch = GLES20.glGetAttribLocation(programId, "aTextureCoord");
        GLES20.glEnableVertexAttribArray(tch);
        GLES20.glVertexAttribPointer(tch, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        EGL14.eglSwapBuffers(eglDisplay, surface);
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: " + GLES20.glGetProgramInfoLog(program));
        }
        return program;
    }

    public void release() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        glHandler.post(() -> {
            try {
                if (cameraTexture != null) {
                    cameraTexture.release();
                    cameraTexture = null;
                }
                if (cameraSurface != null) {
                    cameraSurface.release();
                    cameraSurface = null;
                }
                if (programId != -1) {
                    GLES20.glDeleteProgram(programId);
                    programId = -1;
                }

                if (eglHqSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglHqSurface);
                    eglHqSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglLqSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglLqSurface);
                    eglLqSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }

                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
                eglDisplay = EGL14.EGL_NO_DISPLAY;

            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        glThread.quitSafely();
        try {
            glThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}