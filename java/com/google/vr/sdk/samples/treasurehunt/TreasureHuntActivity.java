/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vr.sdk.samples.treasurehunt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.vr.sdk.audio.GvrAudioEngine;
import com.google.vr.sdk.base.AndroidCompat;
import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Set;
import java.util.UUID;

import javax.microedition.khronos.egl.EGLConfig;

public class TreasureHuntActivity extends GvrActivity implements Runnable, View.OnClickListener, GvrView.StereoRenderer {

  protected float[] modelCube;
  protected float[] modelPosition;

  private static final String TAG = "TreasureHuntActivity";

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100.0f;

  private static final float CAMERA_Z = 0.01f;

  private static final float YAW_LIMIT = 0.12f;
  private static final float PITCH_LIMIT = 0.12f;

  private static final int COORDS_PER_VERTEX = 3;

  // We keep the light always position just above the user.
  private static final float[] LIGHT_POS_IN_WORLD_SPACE = new float[] {0.0f, 2.0f, 0.0f, 1.0f};

  // Convenience vector for extracting the position from a matrix via multiplication.
  private static final float[] POS_MATRIX_MULTIPLY_VEC = {0, 0, 0, 1.0f};

  private static final float MIN_MODEL_DISTANCE = 3.0f;
  private static final float MAX_MODEL_DISTANCE = 7.0f;

  private static final String OBJECT_SOUND_FILE = "cube_sound.wav";
  private static final String SUCCESS_SOUND_FILE = "success.wav";

  public final float[] lightPosInEyeSpace = new float[4];//

  private FloatBuffer floorVertices;
  private FloatBuffer floorColors;
  private FloatBuffer floorNormals;

  private FloatBuffer cubeVertices;
  private FloatBuffer cubeColors;
  private FloatBuffer cubeFoundColors;
  private FloatBuffer cubeNormals;

  private int cubeProgram;
  private int floorProgram;

  private int cubePositionParam;
  private int cubeNormalParam;
  private int cubeColorParam;
  private int cubeModelParam;
  private int cubeModelViewParam;
  private int cubeModelViewProjectionParam;
  private int cubeLightPosParam;

  private int floorPositionParam;
  private int floorNormalParam;
  private int floorColorParam;
  private int floorModelParam;
  private int floorModelViewParam;
  private int floorModelViewProjectionParam;
  private int floorLightPosParam;

  private float[] camera;
  private float[] view;
  private float[] headView;
  public float[] modelViewProjection;//
  public float[] modelView;//
  private float[] modelFloor;

  private float[] tempPosition;
  private float[] headRotation;

  private float objectDistance = MAX_MODEL_DISTANCE / 2.0f;
  private float floorDepth = 20f;

  private Vibrator vibrator;

  private GvrAudioEngine gvrAudioEngine;
  private volatile int sourceId = GvrAudioEngine.INVALID_ID;
  private volatile int successSourceId = GvrAudioEngine.INVALID_ID;


  // Bluetooth Adapter
  private BluetoothAdapter mAdapter;

  // Bluetoothデバイス
  private BluetoothDevice mDevice;

  // Bluetooth UUID
  private final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

  // デバイス名
  private final String DEVICE_NAME = "HC-06";

  // ソケット
  private BluetoothSocket mSocket;

  // Thread
  private Thread mThread;

  // Threadの状態を表す
  private boolean isRunning;

  /** 接続ボタン. */
  private Button connectButton;

  /** Action(ステータス表示). */
  private static final int VIEW_STATUS = 0;

  /** Action(取得文字列). */
  private static final int VIEW_INPUT = 1;

  /** Connect確認用フラグ */
  private boolean connectFlg = false;

  /** BluetoothのOutputStream. */
  OutputStream mmOutputStream = null;

  // センサの値
  String InputVal = "";

  float SRX = 0.0f;
  float SRY = 0.0f;
  float SRZ = 0.0f;

  //Floor f1 = new Floor();

  public int loadGLShader(int type, int resId) {
    /**
     * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
     *
     * @param type The type of shader we will be creating.
     * @param resId The resource ID of the raw text file about to be turned into a shader.
     * @return The shader object handler.
     */
    String code = readRawTextFile(resId);
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, code);
    GLES20.glCompileShader(shader);

    // Get the compilation status.
    final int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

    // If the compilation failed, delete the shader.
    if (compileStatus[0] == 0) {
      Log.e(TAG, "Error compiling shader: " + GLES20.glGetShaderInfoLog(shader));
      GLES20.glDeleteShader(shader);
      shader = 0;
    }

    if (shader == 0) {
      throw new RuntimeException("Error creating shader.");
    }

    return shader;
  }

  private static void checkGLError(String label) {
    /**
     * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
     *
     * @param label Label to report in case of error.
     */
    int error;
    while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
      Log.e(TAG, label + ": glError " + error);
      throw new RuntimeException(label + ": glError " + error);
    }
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    /**
     * Sets the view to our GvrView and initializes the transformation matrices we will use
     * to render our scene.
     */
    super.onCreate(savedInstanceState);

    //initializeGvrView();
    setContentView(R.layout.common_ui);

    GvrView gvrView = (GvrView) findViewById(R.id.gvr_view);
    gvrView.setEGLConfigChooser(8, 8, 8, 8, 16, 8);

    gvrView.setRenderer(this);
    gvrView.setTransitionViewEnabled(true);

    setGvrView(gvrView);

    modelCube = new float[16];
    camera = new float[16];
    view = new float[16];
    modelViewProjection = new float[16];
    modelView = new float[16];
    modelFloor = new float[16];
    tempPosition = new float[4];
    // Model first appears directly in front of user.
    modelPosition = new float[] {0.0f, 0.0f, -MAX_MODEL_DISTANCE / 2.0f};
    headRotation = new float[4];
    headView = new float[16];
    vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

    // Initialize 3D audio engine.
    gvrAudioEngine = new GvrAudioEngine(this, GvrAudioEngine.RenderingMode.BINAURAL_HIGH_QUALITY);


    connectButton = (Button)findViewById(R.id.connectButton);
    // ボタンのイベントハンドラ
    connectButton.setOnClickListener(this);

    // Bluetoothのデバイス名を取得
    // デバイス名は、RNBT-XXXXになるため、
    // DVICE_NAMEでデバイス名を定義
    mAdapter = BluetoothAdapter.getDefaultAdapter();
    //mStatusTextView.setText("SearchDevice");
    System.out.println("SearchDevice");
    Toast.makeText(this, "SearchDevice", Toast.LENGTH_LONG).show();
    Set< BluetoothDevice > devices = mAdapter.getBondedDevices();
    for ( BluetoothDevice device : devices){

      if(device.getName().equals(DEVICE_NAME)){
        //mStatusTextView.setText("find: " + device.getName());
        System.out.println("find: " + device.getName());
        Toast.makeText(this, "find: " + device.getName(), Toast.LENGTH_LONG).show();
        mDevice = device;
      }
    }
  }

  @Override
  public void onSurfaceChanged(int width, int height) {
    Log.i(TAG, "onSurfaceChanged");
  }

  /**
   * Creates the buffers we use to store information about the 3D world.
   *
   * <p>OpenGL doesn't use Java arrays, but rather needs data in a format it can understand.
   * Hence we use ByteBuffers.
   *
   * @param config The EGL configuration used when creating the surface.
   */
  @Override
  public void onSurfaceCreated(EGLConfig config) {
    Log.i(TAG, "onSurfaceCreated");
    //GLES20.glClearColor(0.1f, 0.1f, 0.1f, 0.5f); // Dark background so text shows up well.
    //GLES20.glClearColor(0.65f, 0.62f, 0.44f, 1.0f);     // 168,157,112
    GLES20.glClearColor(0.28f, 0.25f, 0.17f, 1.0f);     // 71,65,43

    ByteBuffer bbVertices = ByteBuffer.allocateDirect(WorldLayoutData.CUBE_COORDS.length * 4);
    bbVertices.order(ByteOrder.nativeOrder());
    cubeVertices = bbVertices.asFloatBuffer();
    cubeVertices.put(WorldLayoutData.CUBE_COORDS);
    cubeVertices.position(0);

    ByteBuffer bbColors = ByteBuffer.allocateDirect(WorldLayoutData.CUBE_COLORS.length * 4);
    bbColors.order(ByteOrder.nativeOrder());
    cubeColors = bbColors.asFloatBuffer();
    cubeColors.put(WorldLayoutData.CUBE_COLORS);
    cubeColors.position(0);

    ByteBuffer bbFoundColors =
            ByteBuffer.allocateDirect(WorldLayoutData.CUBE_FOUND_COLORS.length * 4);
    bbFoundColors.order(ByteOrder.nativeOrder());
    cubeFoundColors = bbFoundColors.asFloatBuffer();
    cubeFoundColors.put(WorldLayoutData.CUBE_FOUND_COLORS);
    cubeFoundColors.position(0);

    ByteBuffer bbNormals = ByteBuffer.allocateDirect(WorldLayoutData.CUBE_NORMALS.length * 4);
    bbNormals.order(ByteOrder.nativeOrder());
    cubeNormals = bbNormals.asFloatBuffer();
    cubeNormals.put(WorldLayoutData.CUBE_NORMALS);
    cubeNormals.position(0);

    // make a floor
    ByteBuffer bbFloorVertices = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_COORDS.length * 4);
    bbFloorVertices.order(ByteOrder.nativeOrder());
    floorVertices = bbFloorVertices.asFloatBuffer();
    floorVertices.put(WorldLayoutData.FLOOR_COORDS);
    floorVertices.position(0);

    ByteBuffer bbFloorNormals = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_NORMALS.length * 4);
    bbFloorNormals.order(ByteOrder.nativeOrder());
    floorNormals = bbFloorNormals.asFloatBuffer();
    floorNormals.put(WorldLayoutData.FLOOR_NORMALS);
    floorNormals.position(0);

    ByteBuffer bbFloorColors = ByteBuffer.allocateDirect(WorldLayoutData.FLOOR_COLORS.length * 4);
    bbFloorColors.order(ByteOrder.nativeOrder());
    floorColors = bbFloorColors.asFloatBuffer();
    floorColors.put(WorldLayoutData.FLOOR_COLORS);
    floorColors.position(0);

    int vertexShader = loadGLShader(GLES20.GL_VERTEX_SHADER, R.raw.light_vertex);
    int gridShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.grid_fragment);
    int passthroughShader = loadGLShader(GLES20.GL_FRAGMENT_SHADER, R.raw.passthrough_fragment);

    cubeProgram = GLES20.glCreateProgram();
    GLES20.glAttachShader(cubeProgram, vertexShader);
    GLES20.glAttachShader(cubeProgram, passthroughShader);
    GLES20.glLinkProgram(cubeProgram);
    GLES20.glUseProgram(cubeProgram);

    checkGLError("Cube program");

    cubePositionParam = GLES20.glGetAttribLocation(cubeProgram, "a_Position");
    cubeNormalParam = GLES20.glGetAttribLocation(cubeProgram, "a_Normal");
    cubeColorParam = GLES20.glGetAttribLocation(cubeProgram, "a_Color");

    cubeModelParam = GLES20.glGetUniformLocation(cubeProgram, "u_Model");
    cubeModelViewParam = GLES20.glGetUniformLocation(cubeProgram, "u_MVMatrix");
    cubeModelViewProjectionParam = GLES20.glGetUniformLocation(cubeProgram, "u_MVP");
    cubeLightPosParam = GLES20.glGetUniformLocation(cubeProgram, "u_LightPos");

    checkGLError("Cube program params");

    floorProgram = GLES20.glCreateProgram();
    GLES20.glAttachShader(floorProgram, vertexShader);
    GLES20.glAttachShader(floorProgram, gridShader);
    GLES20.glLinkProgram(floorProgram);
    GLES20.glUseProgram(floorProgram);

    checkGLError("Floor program");

    floorModelParam = GLES20.glGetUniformLocation(floorProgram, "u_Model");
    floorModelViewParam = GLES20.glGetUniformLocation(floorProgram, "u_MVMatrix");
    floorModelViewProjectionParam = GLES20.glGetUniformLocation(floorProgram, "u_MVP");
    floorLightPosParam = GLES20.glGetUniformLocation(floorProgram, "u_LightPos");

    floorPositionParam = GLES20.glGetAttribLocation(floorProgram, "a_Position");
    floorNormalParam = GLES20.glGetAttribLocation(floorProgram, "a_Normal");
    floorColorParam = GLES20.glGetAttribLocation(floorProgram, "a_Color");

    checkGLError("Floor program params");

    Matrix.setIdentityM(modelFloor, 0);
    Matrix.translateM(modelFloor, 0, 0, -floorDepth, 0); // Floor appears below user.

    //f1.init();

    // Avoid any delays during start-up due to decoding of sound files.
    new Thread(
            new Runnable() {
              @Override
              public void run() {
                // Start spatial audio playback of OBJECT_SOUND_FILE at the model position. The
                // returned sourceId handle is stored and allows for repositioning the sound object
                // whenever the cube position changes.
                gvrAudioEngine.preloadSoundFile(OBJECT_SOUND_FILE);
                sourceId = gvrAudioEngine.createSoundObject(OBJECT_SOUND_FILE);
                gvrAudioEngine.setSoundObjectPosition(sourceId, modelPosition[0], modelPosition[1], modelPosition[2]);
                gvrAudioEngine.playSound(sourceId, true /* looped playback */);
                // Preload an unspatialized sound to be played on a successful trigger on the cube.
                gvrAudioEngine.preloadSoundFile(SUCCESS_SOUND_FILE);
              }
            })
            .start();

    checkGLError("onSurfaceCreated");
  }

  //  テキストファイルの行を文字列に変換する
  private String readRawTextFile(int resId) {
    /**
     * Converts a raw text file into a string.
     *
     * @param resId The resource ID of the raw text file about to be turned into a shader.
     * @return The context of the text file, or null in case of error.
     */
    InputStream inputStream = getResources().openRawResource(resId);
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append("\n");
      }
      reader.close();
      return sb.toString();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Prepares OpenGL ES before we draw a frame.
   * @param headTransform The head transformation in the new frame.
   */
  @Override
  public void onNewFrame(HeadTransform headTransform) {
    //setCubeRotation();

    // Build the camera matrix and apply it to the ModelView.
    Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, CAMERA_Z, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

    headTransform.getHeadView(headView, 0);

    // Update the 3d audio engine with the most recent head rotation.
    headTransform.getQuaternion(headRotation, 0);
    gvrAudioEngine.setHeadRotation(headRotation[0], headRotation[1], headRotation[2], headRotation[3]);
    // Regular update call to GVR audio engine.
    gvrAudioEngine.update();

    checkGLError("onReadyToDraw");
  }


  /**
   * Draws a frame for an eye.
   *
   * @param eye The eye to render. Includes all required transformations.
   */
  @Override
  public void onDrawEye(Eye eye) {
    GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    checkGLError("colorParam");

    // Apply the eye transformation to the camera.
    Matrix.multiplyMM(view, 0, eye.getEyeView(), 0, camera, 0);

    // Set the position of the light
    Matrix.multiplyMV(lightPosInEyeSpace, 0, view, 0, LIGHT_POS_IN_WORLD_SPACE, 0);

    // Build the ModelView and ModelViewProjection matrices
    // for calculating cube position and light.
    float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);

    //Matrix.setRotateM(modelCube, 0, SRZ , 0.0f, 0.0f, 1.0f);
    Matrix.setIdentityM(modelCube, 0);
    Matrix.translateM(modelCube, 0, 0f, 0f, 4.2f);
    Matrix.rotateM(modelCube, 0, SRZ, 1.0f, 0.0f, 0.0f);
    Matrix.rotateM(modelCube, 0, SRY, 0.0f, 1.0f, 0.0f);
    Matrix.rotateM(modelCube, 0, SRX, 0.0f, 0.0f, 1.0f);

    Matrix.multiplyMM(modelView, 0, view, 0, modelCube, 0);
    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);

    drawCube();

    // Set modelView for the floor, so we draw floor in the correct location
    Matrix.multiplyMM(modelView, 0, view, 0, modelFloor, 0);
    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
    drawFloor();
    //f1.draw();
  }

  public void run() {
    InputStream mmInStream = null;

    Message valueMsg = new Message();
    valueMsg.what = VIEW_STATUS;
    valueMsg.obj = "connecting...";
    //System.out.println(valueMsg);
    mHandler.sendMessage(valueMsg);

    try{

      // 取得したデバイス名を使ってBluetoothでSocket接続
      mSocket = mDevice.createRfcommSocketToServiceRecord(MY_UUID);
      mSocket.connect();
      mmInStream = mSocket.getInputStream();
      mmOutputStream = mSocket.getOutputStream();

      // InputStreamのバッファを格納
      byte[] buffer = new byte[1024];
      //byte[] buffer = new byte[2048];

      // 取得したバッファのサイズを格納
      int bytes;
      valueMsg = new Message();
      valueMsg.what = VIEW_STATUS;
      valueMsg.obj = "connected.";
      //System.out.println(valueMsg);
      mHandler.sendMessage(valueMsg);

      connectFlg = true;

      while(isRunning){

        // InputStreamの読み込み
        bytes = mmInStream.read(buffer);
        Log.i(TAG,"bytes="+bytes);
        // String型に変換
        String readMsg = new String(buffer, 0, bytes);

        // null以外なら表示
        if(readMsg.trim() != null && !readMsg.trim().equals("")){
          Log.i(TAG,"value="+readMsg.trim());

          valueMsg = new Message();
          valueMsg.what = VIEW_INPUT;
          valueMsg.obj = readMsg;
          //System.out.println("L190"+valueMsg);
          mHandler.sendMessage(valueMsg);
        }
        else{
          // Log.i(TAG,"value=nodata");
        }

      }
    }catch(Exception e){

      valueMsg = new Message();
      valueMsg.what = VIEW_STATUS;
      valueMsg.obj = "Error1:" + e;
      //System.out.println(valueMsg);
      //mHandler.sendMessage(valueMsg);

      try{
        mSocket.close();
      }catch(Exception ee){}
      isRunning = false;
      connectFlg = false;
    }
  }

  @Override
  public void onFinishFrame(Viewport viewport) {}

  /**
   * Draw the cube.
   *
   * <p>We've set all of our transformation matrices. Now we simply pass them into the shader.
   */
  public void drawCube() {
    GLES20.glUseProgram(cubeProgram);

    GLES20.glUniform3fv(cubeLightPosParam, 1, lightPosInEyeSpace, 0);

    // Set the Model in the shader, used to calculate lighting
    GLES20.glUniformMatrix4fv(cubeModelParam, 1, false, modelCube, 0);

    // Set the ModelView in the shader, used to calculate lighting
    GLES20.glUniformMatrix4fv(cubeModelViewParam, 1, false, modelView, 0);

    // Set the position of the cube
    GLES20.glVertexAttribPointer(
            cubePositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, cubeVertices);

    // Set the ModelViewProjection matrix in the shader.
    GLES20.glUniformMatrix4fv(cubeModelViewProjectionParam, 1, false, modelViewProjection, 0);

    // Set the normal positions of the cube, again for shading
    GLES20.glVertexAttribPointer(cubeNormalParam, 3, GLES20.GL_FLOAT, false, 0, cubeNormals);
    GLES20.glVertexAttribPointer(cubeColorParam, 4, GLES20.GL_FLOAT, false, 0,
            isLookingAtObject() ? cubeFoundColors : cubeColors);

    // Enable vertex arrays
    GLES20.glEnableVertexAttribArray(cubePositionParam);
    GLES20.glEnableVertexAttribArray(cubeNormalParam);
    GLES20.glEnableVertexAttribArray(cubeColorParam);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);

    // Disable vertex arrays
    GLES20.glDisableVertexAttribArray(cubePositionParam);
    GLES20.glDisableVertexAttribArray(cubeNormalParam);
    GLES20.glDisableVertexAttribArray(cubeColorParam);

    checkGLError("Drawing cube");
  }

  /**
   * Draw the floor.
   *
   * <p>This feeds in data for the floor into the shader. Note that this doesn't feed in data about
   * position of the light, so if we rewrite our code to draw the floor first, the lighting might
   * look strange.
   */
  public void drawFloor() {
    GLES20.glUseProgram(floorProgram);

    // Set ModelView, MVP, position, normals, and color.
    GLES20.glUniform3fv(floorLightPosParam, 1, lightPosInEyeSpace, 0);
    GLES20.glUniformMatrix4fv(floorModelParam, 1, false, modelFloor, 0);
    GLES20.glUniformMatrix4fv(floorModelViewParam, 1, false, modelView, 0);
    GLES20.glUniformMatrix4fv(floorModelViewProjectionParam, 1, false, modelViewProjection, 0);
    GLES20.glVertexAttribPointer(
            floorPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, floorVertices);
    GLES20.glVertexAttribPointer(floorNormalParam, 3, GLES20.GL_FLOAT, false, 0, floorNormals);
    GLES20.glVertexAttribPointer(floorColorParam, 4, GLES20.GL_FLOAT, false, 0, floorColors);

    GLES20.glEnableVertexAttribArray(floorPositionParam);
    GLES20.glEnableVertexAttribArray(floorNormalParam);
    GLES20.glEnableVertexAttribArray(floorColorParam);

    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 24);

    GLES20.glDisableVertexAttribArray(floorPositionParam);
    GLES20.glDisableVertexAttribArray(floorNormalParam);
    GLES20.glDisableVertexAttribArray(floorColorParam);

    checkGLError("drawing floor");
  }

  @Override
  public void onCardboardTrigger() {
    /**
     * Called when the Cardboard trigger is pulled.
     */
    Log.i(TAG, "onCardboardTrigger");

    if (isLookingAtObject()) {
      successSourceId = gvrAudioEngine.createStereoSound(SUCCESS_SOUND_FILE);
      gvrAudioEngine.playSound(successSourceId, false /* looping disabled */);
      hideObject();
    }

    // Always give user feedback.
    vibrator.vibrate(50);
  }

  protected void hideObject() {
    /**
     * Find a new random position for the object.
     *
     * <p>We'll rotate it around the Y-axis so it's out of sight, and then up or down by a little bit.
     */
    float[] rotationMatrix = new float[16];
    float[] posVec = new float[4];

    // First rotate in XZ plane, between 90 and 270 deg away, and scale so that we vary
    // the object's distance from the user.
    float angleXZ = (float) Math.random() * 180 + 90;
    Matrix.setRotateM(rotationMatrix, 0, angleXZ, 0f, 1f, 0f);
    float oldObjectDistance = objectDistance;
    objectDistance =
            (float) Math.random() * (MAX_MODEL_DISTANCE - MIN_MODEL_DISTANCE) + MIN_MODEL_DISTANCE;
    float objectScalingFactor = objectDistance / oldObjectDistance;
    Matrix.scaleM(rotationMatrix, 0, objectScalingFactor, objectScalingFactor, objectScalingFactor);
    Matrix.multiplyMV(posVec, 0, rotationMatrix, 0, modelCube, 12);

    float angleY = (float) Math.random() * 80 - 40; // Angle in Y plane, between -40 and 40.
    angleY = (float) Math.toRadians(angleY);
    float newY = (float) Math.tan(angleY) * objectDistance;

    modelPosition[0] = posVec[0];
    modelPosition[1] = newY;
    modelPosition[2] = posVec[2];

  }

  /**
   * Check if user is looking at object by calculating where the object is in eye-space.
   *
   * @return true if the user is looking at the object.
   */
  private boolean isLookingAtObject() {
    // Convert object space to camera space. Use the headView from onNewFrame.
    Matrix.multiplyMM(modelView, 0, headView, 0, modelCube, 0);
    Matrix.multiplyMV(tempPosition, 0, modelView, 0, POS_MATRIX_MULTIPLY_VEC, 0);

    float pitch = (float) Math.atan2(tempPosition[1], -tempPosition[2]);
    float yaw = (float) Math.atan2(tempPosition[0], -tempPosition[2]);

    return Math.abs(pitch) < PITCH_LIMIT && Math.abs(yaw) < YAW_LIMIT;
  }

  private boolean SensFlag = false;
  Handler mHandler = new Handler() {
    @Override
    public void handleMessage(Message msg) {
      int action = msg.what;
      String msgStr = (String)msg.obj;
      if(action == VIEW_INPUT){
        //mInputTextView.setText(msgStr);
        System.out.println("VIEW_INPUT:"+msgStr);

        // connectButtonの非表示
        connectButton.setVisibility(View.INVISIBLE);

        if (msgStr.equals("r")){
          System.out.println(InputVal);
          if (SensFlag){
            try{
              SRX = Float.parseFloat(InputVal.split(" ")[1]);
              SRY = Float.parseFloat(InputVal.split(" ")[2]);
              SRZ = Float.parseFloat(InputVal.split(" ")[3]);
            }catch (ArrayIndexOutOfBoundsException e){
              System.out.print("上手く取れない");
            }

          }else{
            SensFlag = true;
          }

          InputVal = "";
        }else{
          InputVal += msgStr;
        }
      }
      else if(action == VIEW_STATUS){
        //mStatusTextView.setText(msgStr);
        System.out.println("VIEW_STATUS:"+msgStr);

      }
    }
  };


  @Override
  public void onClick(View v) {
    if(v.equals(connectButton)) {
      // 接続されていない場合のみ
      if (!connectFlg) {
        //mStatusTextView.setText("try connect");
        System.out.println("try connect");
        //Toast.makeText(this, "try connect", Toast.LENGTH_LONG).show();

        mThread = new Thread((Runnable) this);
        // Threadを起動し、Bluetooth接続
        isRunning = true;
        mThread.start();
      }
    }
  }

  @Override
  public void onPause() {
    gvrAudioEngine.pause();
    super.onPause();
    try{
      mSocket.close();
    }
    catch(Exception e){}
  }

  @Override
  public void onResume() {
    super.onResume();
    gvrAudioEngine.resume();
  }

  @Override
  public void onRendererShutdown() {
    Log.i(TAG, "onRendererShutdown");
  }

}
