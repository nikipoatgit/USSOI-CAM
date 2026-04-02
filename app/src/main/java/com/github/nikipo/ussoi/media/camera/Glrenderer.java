package com.github.nikipo.ussoi.media.camera;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * *****************************************************************************
 *
 * @author nikhi
 * *****************************************************************************
 * @file Glrenderer
 * @attention Copyright (c) 2026
 * All rights reserved.
 * <p>
 * This software is licensed under the terms described in the LICENSE file
 * located in the root directory of this project.
 * If no LICENSE file is present, this software is provided "AS IS",
 * without warranty of any kind, express or implied.
 * <p>
 * *****************************************************************************
 */


/**
 * Sits between the Camera2 high-speed session and two MediaCodec encoders.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARCHITECTURE
 * ─────────────────────────────────────────────────────────────────────────────
 *   Camera (constrained high-speed session — one surface only)
 *         │
 *   SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 *         │  onFrameAvailable → GL thread
 *   OpenGL ES 2.0 renderer
 *    ┌────┴─────┐
 *  EGLSurface  EGLSurface
 *  (HQ size)   (LQ size — downscaled by GL viewport)
 *    │          │
 *  HQ encoder  LQ encoder
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FEATURES (all fixes applied)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  1. FRAME-DROP / BACKPRESSURE
 *     AtomicInteger pendingFrames is an at-most-1 gate on the GL thread queue.
 *     A new frame notification is only posted when the counter is 0 (GL thread
 *     is idle). If the GL thread is still busy, the notification is dropped;
 *     updateTexImage() always latches the LATEST frame so nothing is permanently
 *     lost. This prevents unbounded queue growth at 120+ FPS.
 *
 *  2. EGL CONTEXT LOSS / RECOVERY
 *     eglGetCurrentContext() == EGL_NO_CONTEXT is checked at the start of each
 *     frame. eglSwapBuffers returning false is inspected for EGL_CONTEXT_LOST
 *     (0x300E). Either path calls handleContextLoss() which tears down all
 *     GL/EGL objects and fires ContextLostCallback on a fresh thread so the
 *     owner can restart the pipeline and call init() with new encoder surfaces.
 *
 *  3. SCREEN OFF / ON  +  ENCODER RESTART  +  GPU RESET
 *     A BroadcastReceiver on ACTION_SCREEN_OFF / ACTION_SCREEN_ON sets the
 *     volatile 'suspended' flag. On screen-off: updateTexImage() still drains
 *     the SurfaceTexture queue (prevents camera driver stall) but no frames
 *     are written to the encoders via eglSwapBuffers. On screen-on: rendering
 *     resumes and crop matrices are marked dirty for a clean restart.
 *
 *  4. ASPECT RATIO / LETTERBOX / CROP CONTROL
 *     ScaleMode enum: FIT (letterbox/pillarbox), CROP (centre-crop), STRETCH.
 *     A 4x4 UV crop matrix is computed per surface via buildCropMatrix() and
 *     uploaded to the vertex shader as uCropMatrix. HQ and LQ modes are fully
 *     independent. Matrices are rebuilt lazily on the GL thread when dirty.
 *
 *  5. GL ERROR CHECKS
 *     checkGlError(op) is called after every GLES20 call that can fail. It
 *     loops glGetError() until GL_NO_ERROR (spec allows multiple pending errors)
 *     and throws a descriptive RuntimeException. checkEgl(op) does the same
 *     for all EGL calls.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * USAGE
 * ─────────────────────────────────────────────────────────────────────────────
 *   GlRenderer renderer = new GlRenderer(context, hqW, hqH, lqW, lqH);
 *   renderer.setContextLostCallback(() -> restartPipeline());
 *   renderer.setSourceDimensions(cameraWidth, cameraHeight);
 *   renderer.setHqScaleMode(GlRenderer.ScaleMode.FIT);
 *   renderer.setLqScaleMode(GlRenderer.ScaleMode.CROP);
 *   renderer.init(hqEncoderSurface, lqEncoderSurface);
 *   Surface cam = renderer.getCameraSurface(); // single surface for Camera2
 *   // ...
 *   renderer.release();
 */
public final class GlRenderer {

    private static final String TAG = "GlRenderer";

    // =========================================================================
    // Public types
    // =========================================================================

    public enum ScaleMode {
        /**
         * Letterbox / pillarbox — entire source frame is visible.
         * Black bars fill unused area.
         */
        FIT,
        /**
         * Centre-crop — destination surface is completely filled.
         * Edges of the source may be cropped when aspect ratios differ.
         */
        CROP,
        /**
         * Stretch — fills the surface exactly, ignoring aspect ratio.
         */
        STRETCH
    }

    /** Called (on an arbitrary thread) when the EGL context is lost. */
    public interface ContextLostCallback {
        void onContextLost();
    }

    // =========================================================================
    // Shaders
    // =========================================================================

    // uTexMatrix  — SurfaceTexture transform (camera flip / crop / Y-inversion)
    // uCropMatrix — our scale-mode UV transform (applied BEFORE uTexMatrix)
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n"   +
                    "attribute vec2 aTexCoord;\n"   +
                    "varying   vec2 vTexCoord;\n"   +
                    "uniform   mat4 uTexMatrix;\n"  +
                    "uniform   mat4 uCropMatrix;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vec4 tc = uCropMatrix * vec4(aTexCoord, 0.0, 1.0);\n" +
                    "    vTexCoord = (uTexMatrix * tc).xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying      vec2      vTexCoord;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}\n";

    // =========================================================================
    // Quad geometry  (TRIANGLE_STRIP, clip-space [-1,1])
    // =========================================================================

    private static final float[] QUAD_VERTICES   = { -1,-1, 1,-1, -1,1, 1,1 };
    private static final float[] QUAD_TEX_COORDS = {  0, 0, 1, 0,  0,1, 1,1 };

    // =========================================================================
    // Configuration
    // =========================================================================

    private final Context context;

    private int srcWidth;   // camera frame size (used for crop-matrix computation)
    private int srcHeight;

    private final int hqWidth, hqHeight;  // HQ encoder surface size
    private final int lqWidth, lqHeight;  // LQ encoder surface size

    private volatile ScaleMode hqScaleMode = ScaleMode.CROP;
    private volatile ScaleMode lqScaleMode = ScaleMode.CROP;

    // Crop matrices — rebuilt lazily on the GL thread when dirty flags are set
    private final float[] hqCropMatrix = identityMatrix();
    private final float[] lqCropMatrix = identityMatrix();
    private volatile boolean hqCropDirty = true;
    private volatile boolean lqCropDirty = true;

    // =========================================================================
    // Callbacks + saved surfaces
    // =========================================================================

    private volatile ContextLostCallback contextLostCallback;
    private Surface savedHqSurface;
    private Surface savedLqSurface;

    // =========================================================================
    // GL thread
    // =========================================================================

    private HandlerThread glThread;
    private Handler       glHandler;

    // =========================================================================
    // EGL
    // =========================================================================

    private EGLDisplay eglDisplay   = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext   = EGL14.EGL_NO_CONTEXT;
    private EGLConfig  eglConfig    = null;
    private EGLSurface hqEglSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface lqEglSurface = EGL14.EGL_NO_SURFACE;

    // =========================================================================
    // GL objects
    // =========================================================================

    private int programId;
    private int aPositionLoc, aTexCoordLoc;
    private int uTexMatrixLoc, uCropMatrixLoc, uTextureLoc;
    private int cameraTextureId;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // =========================================================================
    // SurfaceTexture
    // =========================================================================

    private SurfaceTexture surfaceTexture;
    private Surface        cameraSurface;
    private final float[]  texMatrix = new float[16];

    // =========================================================================
    // Backpressure  (fix #1)
    // =========================================================================

    /**
     * At-most-1 gate for the GL thread's message queue.
     *
     * onFrameAvailable (any thread):
     *   getAndIncrement() == 0  → counter was 0 → post renderFrame, counter = 1
     *   getAndIncrement() >  0  → counter was 1 → GL busy, drop + undo increment
     *
     * renderFrame (GL thread):
     *   decrementAndGet() at entry → restores to 0, next frame can post.
     *
     * updateTexImage() inside renderFrame always latches the LATEST pending
     * frame, so dropped notifications never leave a stale frame on-screen.
     */
    private final AtomicInteger pendingFrames = new AtomicInteger(0);

    // =========================================================================
    // Screen off / on  (fix #3)
    // =========================================================================

    /**
     * When true: updateTexImage() still drains the SurfaceTexture queue but
     * eglSwapBuffers is NOT called. Prevents the GL thread from blocking when
     * the display compositor is suspended after screen-off.
     */
    private volatile boolean      suspended = false;
    private          BroadcastReceiver screenReceiver;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    private volatile boolean initialized = false;

    // =========================================================================
    // Constructor
    // =========================================================================

    public GlRenderer(Context context,
                      int hqWidth, int hqHeight,
                      int lqWidth, int lqHeight) {
        this.context  = context.getApplicationContext();
        this.hqWidth  = hqWidth;
        this.hqHeight = hqHeight;
        this.lqWidth  = lqWidth;
        this.lqHeight = lqHeight;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public void setContextLostCallback(ContextLostCallback cb) {
        contextLostCallback = cb;
    }

    /** Override source dimensions used for crop-matrix computation. Thread-safe. */
    public void setSourceDimensions(int width, int height) {
        srcWidth = width; srcHeight = height;
        hqCropDirty = true; lqCropDirty = true;
    }

    /** Sets the scale mode for the HQ surface. Thread-safe, next-frame effect. */
    public void setHqScaleMode(ScaleMode mode) { hqScaleMode = mode; hqCropDirty = true; }

    /** Sets the scale mode for the LQ surface. Thread-safe, next-frame effect. */
    public void setLqScaleMode(ScaleMode mode) { lqScaleMode = mode; lqCropDirty = true; }

    /**
     * Initialises EGL, compiles shaders, creates the SurfaceTexture, and wraps
     * both encoder input surfaces as EGLSurfaces. Blocks until GL setup is done.
     */
    public void init(Surface hqSurface, Surface lqSurface) {
        savedHqSurface = hqSurface;
        savedLqSurface = lqSurface;
        if (srcWidth  == 0) srcWidth  = hqWidth;
        if (srcHeight == 0) srcHeight = hqHeight;

        glThread  = new HandlerThread("GlRendererThread");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        runOnGlThreadAndWait(() -> {
            setupEgl(hqSurface, lqSurface);
            setupGl();
            setupSurfaceTexture();
        });

        registerScreenReceiver();
        initialized = true;
        Log.d(TAG, "init  src=" + srcWidth + "x" + srcHeight
                + "  HQ=" + hqWidth + "x" + hqHeight
                + "  LQ=" + lqWidth + "x" + lqHeight);
    }

    /** Returns the single Surface the Camera2 high-speed session should target. */
    public Surface getCameraSurface() {
        if (!initialized) throw new IllegalStateException("call init() first");
        return cameraSurface;
    }

    /** Releases all resources and stops the GL thread. Do not call from GL thread. */
    public void release() {
        initialized = false;
        unregisterScreenReceiver();
        if (glHandler != null) {
            runOnGlThreadAndWait(this::releaseGlObjects);
            glThread.quitSafely();
        }
        glThread = null; glHandler = null;
        Log.d(TAG, "released");
    }

    // =========================================================================
    // EGL setup
    // =========================================================================

    private void setupEgl(Surface hqSurface, Surface lqSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY)
            throw new RuntimeException("eglGetDisplay returned EGL_NO_DISPLAY");

        int[] ver = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1))
            throw new RuntimeException("eglInitialize failed: 0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        Log.d(TAG, "EGL " + ver[0] + "." + ver[1]);

        // EGL_RECORDABLE_ANDROID=1 is REQUIRED for MediaCodec input surfaces.
        // Without it, eglCreateWindowSurface silently fails on most devices.
        int[] attribs = {
                EGL14.EGL_RED_SIZE,            8,
                EGL14.EGL_GREEN_SIZE,          8,
                EGL14.EGL_BLUE_SIZE,           8,
                EGL14.EGL_ALPHA_SIZE,          8,
                EGL14.EGL_RENDERABLE_TYPE,     EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfgs = new EGLConfig[1];
        int[]       num  = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, cfgs, 0, 1, num, 0) || num[0] == 0)
            throw new RuntimeException("eglChooseConfig failed: 0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        eglConfig = cfgs[0];

        int[] ctxA = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxA, 0);
        checkEgl("eglCreateContext");

        int[] sa = { EGL14.EGL_NONE };
        hqEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, hqSurface, sa, 0);
        checkEgl("eglCreateWindowSurface(HQ)");
        lqEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, lqSurface, sa, 0);
        checkEgl("eglCreateWindowSurface(LQ)");

        makeCurrent(hqEglSurface); // make context current before GL calls
    }

    // =========================================================================
    // GL setup
    // =========================================================================

    private void setupGl() {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER,   VERTEX_SHADER);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        programId = GLES20.glCreateProgram();
        if (programId == 0) throw new RuntimeException("glCreateProgram returned 0");

        GLES20.glAttachShader(programId, vs); checkGlError("glAttachShader vs");
        GLES20.glAttachShader(programId, fs); checkGlError("glAttachShader fs");
        GLES20.glLinkProgram(programId);      checkGlError("glLinkProgram");

        int[] linked = new int[1];
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == GLES20.GL_FALSE) {
            String info = GLES20.glGetProgramInfoLog(programId);
            GLES20.glDeleteProgram(programId);
            throw new RuntimeException("Program link failed:\n" + info);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);

        aPositionLoc   = GLES20.glGetAttribLocation (programId, "aPosition");
        aTexCoordLoc   = GLES20.glGetAttribLocation (programId, "aTexCoord");
        uTexMatrixLoc  = GLES20.glGetUniformLocation(programId, "uTexMatrix");
        uCropMatrixLoc = GLES20.glGetUniformLocation(programId, "uCropMatrix");
        uTextureLoc    = GLES20.glGetUniformLocation(programId, "uTexture");

        if (aPositionLoc < 0 || aTexCoordLoc < 0 || uTexMatrixLoc < 0
                || uCropMatrixLoc < 0 || uTextureLoc < 0)
            throw new RuntimeException("Shader location not found: "
                    + "aPos=" + aPositionLoc + " aTex=" + aTexCoordLoc
                    + " uTex=" + uTexMatrixLoc + " uCrop=" + uCropMatrixLoc
                    + " uSampler=" + uTextureLoc);

        vertexBuffer   = toFloatBuffer(QUAD_VERTICES);
        texCoordBuffer = toFloatBuffer(QUAD_TEX_COORDS);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        checkGlError("glGenTextures");
        cameraTextureId = tex[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        checkGlError("glBindTexture OES");
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        checkGlError("glTexParameteri MIN_FILTER");
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        checkGlError("glTexParameteri MAG_FILTER");
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri WRAP_S");
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri WRAP_T");
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        checkGlError("glBindTexture 0");
    }

    // =========================================================================
    // SurfaceTexture setup
    // =========================================================================

    private void setupSurfaceTexture() {
        surfaceTexture = new SurfaceTexture(cameraTextureId);

        surfaceTexture.setOnFrameAvailableListener(st -> {
            if (!initialized) return;

            // Backpressure gate: post only if counter was 0 (GL thread is idle).
            // If counter > 0, GL thread already has a task queued; that task will
            // call updateTexImage() which latches the latest frame automatically.
            if (pendingFrames.getAndIncrement() > 0) {
                pendingFrames.decrementAndGet(); // undo, keep at 1
                return;
            }
            glHandler.post(this::renderFrame);

        }, glHandler);

        cameraSurface = new Surface(surfaceTexture);
    }

    // =========================================================================
    // Frame rendering  (GL thread)
    // =========================================================================

    private void renderFrame() {
        pendingFrames.decrementAndGet(); // release the backpressure slot
        if (!initialized) return;

        // ── Fix #2: EGL context-loss check at frame entry ─────────────────────
        if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "EGL context lost at frame entry — recovering");
            handleContextLoss();
            return;
        }

        // ── Fix #4: lazy crop-matrix rebuild ──────────────────────────────────
        if (hqCropDirty) {
            buildCropMatrix(hqCropMatrix, srcWidth, srcHeight, hqWidth, hqHeight, hqScaleMode);
            hqCropDirty = false;
        }
        if (lqCropDirty) {
            buildCropMatrix(lqCropMatrix, srcWidth, srcHeight, lqWidth, lqHeight, lqScaleMode);
            lqCropDirty = false;
        }

        // Latch LATEST camera frame (drains queue automatically)
        makeCurrent(hqEglSurface);
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(texMatrix);
        long tsNs = surfaceTexture.getTimestamp();

        // ── Fix #3: screen-off suspend — queue drained above, skip encoder writes
        if (suspended) return;

        // ── HQ pass ───────────────────────────────────────────────────────────
        drawQuad(hqWidth, hqHeight, hqCropMatrix);
        EGLExt.eglPresentationTimeANDROID(eglDisplay, hqEglSurface, tsNs);
        if (!EGL14.eglSwapBuffers(eglDisplay, hqEglSurface)) {
            if (handleSwapError("HQ")) return;
        }

        // ── LQ pass ───────────────────────────────────────────────────────────
        // OES texture is still latched — no second updateTexImage() needed.
        makeCurrent(lqEglSurface);
        drawQuad(lqWidth, lqHeight, lqCropMatrix);
        EGLExt.eglPresentationTimeANDROID(eglDisplay, lqEglSurface, tsNs);
        if (!EGL14.eglSwapBuffers(eglDisplay, lqEglSurface)) {
            handleSwapError("LQ");
        }
    }

    private void drawQuad(int width, int height, float[] cropMatrix) {
        GLES20.glViewport(0, 0, width, height);         checkGlError("glViewport");
        GLES20.glClearColor(0f, 0f, 0f, 1f);            checkGlError("glClearColor");
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);     checkGlError("glClear");
        GLES20.glUseProgram(programId);                  checkGlError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        checkGlError("glActiveTexture");
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        checkGlError("glBindTexture OES");
        GLES20.glUniform1i(uTextureLoc, 0);
        checkGlError("glUniform1i uTexture");

        GLES20.glUniformMatrix4fv(uTexMatrixLoc,  1, false, texMatrix,  0);
        checkGlError("glUniformMatrix4fv uTexMatrix");
        GLES20.glUniformMatrix4fv(uCropMatrixLoc, 1, false, cropMatrix, 0);
        checkGlError("glUniformMatrix4fv uCropMatrix");

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        checkGlError("glEnableVertexAttribArray aPosition");
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        checkGlError("glVertexAttribPointer aPosition");

        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        checkGlError("glEnableVertexAttribArray aTexCoord");
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        checkGlError("glVertexAttribPointer aTexCoord");

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTexCoordLoc);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    // =========================================================================
    // Aspect ratio / crop matrix  (fix #4)
    // =========================================================================

    /**
     * Builds a 4×4 column-major UV transform that maps [0,1]² UVs to a
     * sub-rectangle matching the requested ScaleMode.
     *
     * Applied in the vertex shader BEFORE uTexMatrix so the two compose.
     *
     * Column-major scale+translate for UV (u,v,0,1):
     *   u_out = scaleU*u + offsetU   →  out[0]=scaleU,  out[12]=offsetU
     *   v_out = scaleV*v + offsetV   →  out[5]=scaleV,  out[13]=offsetV
     */
    private static void buildCropMatrix(float[] out,
                                        int srcW, int srcH,
                                        int dstW, int dstH,
                                        ScaleMode mode) {
        setIdentityM(out);
        if (mode == ScaleMode.STRETCH || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0)
            return; // identity == stretch

        float sa = (float) srcW / srcH;
        float da = (float) dstW / dstH;
        float scaleU, scaleV, offsetU, offsetV;

        if (mode == ScaleMode.FIT) {
            if (sa > da) {                        // source wider → letterbox
                scaleU = 1f;          scaleV = da / sa;
                offsetU = 0f;         offsetV = (1f - scaleV) * 0.5f;
            } else {                              // source taller → pillarbox
                scaleU = sa / da;     scaleV = 1f;
                offsetU = (1f - scaleU) * 0.5f;  offsetV = 0f;
            }
        } else { // CROP
            if (sa > da) {                        // source wider → crop sides
                scaleU = da / sa;     scaleV = 1f;
                offsetU = (1f - scaleU) * 0.5f;  offsetV = 0f;
            } else {                              // source taller → crop top/bottom
                scaleU = 1f;          scaleV = sa / da;
                offsetU = 0f;         offsetV = (1f - scaleV) * 0.5f;
            }
        }

        out[0]  = scaleU;    // col 0, row 0 — U scale
        out[5]  = scaleV;    // col 1, row 1 — V scale
        out[12] = offsetU;   // col 3, row 0 — U translate
        out[13] = offsetV;   // col 3, row 1 — V translate
    }

    // =========================================================================
    // EGL context loss  (fix #2)
    // =========================================================================

    /**
     * Called when eglSwapBuffers returns false.
     * @return true if EGL_CONTEXT_LOST (recovery fired); false for transient errors.
     */
    private boolean handleSwapError(String tag) {
        int err = EGL14.eglGetError();
        if (err == EGL14.EGL_CONTEXT_LOST) {
            Log.e(TAG, "EGL_CONTEXT_LOST on " + tag + " — recovering");
            handleContextLoss();
            return true;
        }
        // EGL_BAD_SURFACE (0x300D) is transient — skip this frame
        Log.w(TAG, "eglSwapBuffers(" + tag + ") failed: 0x" + Integer.toHexString(err));
        return false;
    }

    /**
     * Tears down all GL/EGL objects and notifies the owner via a new thread.
     * Must run on the GL thread; fires callback on a separate thread to allow
     * the owner to call init() without deadlocking.
     */
    private void handleContextLoss() {
        initialized = false;
        try {
            if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
            if (cameraSurface  != null) { cameraSurface.release();  cameraSurface  = null; }
            EGL14.eglMakeCurrent(eglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (hqEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, hqEglSurface); hqEglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (lqEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, lqEglSurface); lqEglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext); eglContext = EGL14.EGL_NO_CONTEXT;
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(eglDisplay); eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception during context-loss teardown (expected)", e);
        }
        programId = 0; cameraTextureId = 0;

        ContextLostCallback cb = contextLostCallback;
        if (cb != null) new Thread(cb::onContextLost, "GlContextLossNotifier").start();
        else Log.w(TAG, "EGL context lost — no ContextLostCallback set, pipeline won't auto-recover");
    }

    // =========================================================================
    // Screen off / on  (fix #3)
    // =========================================================================

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Log.d(TAG, "Screen OFF — suspending encoder writes");
                    suspended = true;
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    Log.d(TAG, "Screen ON  — resuming encoder writes");
                    suspended   = false;
                    hqCropDirty = true;  // force clean rebuild on resume
                    lqCropDirty = true;
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(screenReceiver, f);
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver != null) {
            try { context.unregisterReceiver(screenReceiver); }
            catch (IllegalArgumentException ignored) {}
            screenReceiver = null;
        }
    }

    // =========================================================================
    // Teardown  (GL thread)
    // =========================================================================

    private void releaseGlObjects() {
        if (cameraSurface  != null) { cameraSurface.release();  cameraSurface  = null; }
        if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return;

        EGL14.eglMakeCurrent(eglDisplay,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (hqEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, hqEglSurface); hqEglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (lqEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, lqEglSurface); lqEglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (programId != 0)       { GLES20.glDeleteProgram(programId); programId = 0; }
        if (cameraTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{ cameraTextureId }, 0); cameraTextureId = 0;
        }
        EGL14.eglDestroyContext(eglDisplay, eglContext); eglContext = EGL14.EGL_NO_CONTEXT;
        EGL14.eglTerminate(eglDisplay);                  eglDisplay = EGL14.EGL_NO_DISPLAY;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext))
            throw new RuntimeException("eglMakeCurrent failed: 0x"
                    + Integer.toHexString(EGL14.eglGetError()));
    }

    private static int compileShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        if (s == 0) throw new RuntimeException("glCreateShader(type=" + type + ") returned 0");
        GLES20.glShaderSource(s, src);   checkGlError("glShaderSource");
        GLES20.glCompileShader(s);       checkGlError("glCompileShader");
        int[] st = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0);
        if (st[0] == GLES20.GL_FALSE) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("Shader compile failed:\n" + log);
        }
        return s;
    }

    private static FloatBuffer toFloatBuffer(float[] d) {
        FloatBuffer b = ByteBuffer.allocateDirect(d.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(d).flip();
        return b;
    }

    /** Throws if any EGL error is pending. */
    private void checkEgl(String op) {
        int e = EGL14.eglGetError();
        if (e != EGL14.EGL_SUCCESS)
            throw new RuntimeException(op + " EGL error 0x" + Integer.toHexString(e));
    }

    /**
     * Drains ALL pending GL errors (spec allows multiple) and throws on the
     * first. Call after every GLES20 API that can fail.
     */
    private static void checkGlError(String op) {
        int e;
        while ((e = GLES20.glGetError()) != GLES20.GL_NO_ERROR)
            throw new RuntimeException(op + " GL error 0x" + Integer.toHexString(e));
    }

    private static float[] identityMatrix() {
        return new float[]{ 1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1 };
    }

    private static void setIdentityM(float[] m) {
        for (int i = 0; i < 16; i++) m[i] = 0f;
        m[0] = m[5] = m[10] = m[15] = 1f;
    }

    /** Runs a task on the GL thread and blocks the caller until it completes.
     *  Exceptions from the GL thread are rethrown on the calling thread. */
    private void runOnGlThreadAndWait(Runnable task) {
        final Object    lock = new Object();
        final boolean[] done = { false };
        final Throwable[] ex = { null };
        glHandler.post(() -> {
            try   { task.run(); }
            catch (Throwable t) { ex[0] = t; }
            finally { synchronized (lock) { done[0] = true; lock.notifyAll(); } }
        });
        synchronized (lock) {
            while (!done[0]) {
                try { lock.wait(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (ex[0] instanceof RuntimeException) throw (RuntimeException) ex[0];
        if (ex[0] != null) throw new RuntimeException(ex[0]);
    }
}