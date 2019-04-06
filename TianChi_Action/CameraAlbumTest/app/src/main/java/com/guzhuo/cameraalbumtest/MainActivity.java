package com.guzhuo.cameraalbumtest;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class MainActivity extends AppCompatActivity
        implements SensorEventListener {

    // 系统相册的路径
    String sysAlbumPath = Environment.getExternalStorageDirectory() +
            File.separator + Environment.DIRECTORY_DCIM +
            File.separator + "Camera" +
            File.separator;

    public static final int TAKE_PHOTO = 1;
    public static final int CHOOSE_PHOTO = 2;

    private ImageView picture;
    private Uri imageUri;  //图片的输出地址

    private SensorManager mSensorManager;
    private TextView mTxtValue1;

    public static final String TAG = "fetchValues";

    private double[] mDeltaAvgAcc = new double[3];  // 任一切片时间内，加速度的变化量
    private double[] mAccVel = new double[3];  // 由连续切片间，累加的速度
    private double[] mDeltaDisp = new double[3];  // 由连续切片间，累加的位移
    private List<Double> mPointsDisp;  // 不同拍照地点之间的距离
    private float[] mPrevAcc = new float[3];  // 前一刻的加速度值
    private double mPrevTime;  // 前一刻的时间
    private double mDeltaTime;  //前后刻的变化时间，一般作切片时间的区长

    private int mTrigger_ResetAcc = 2;  //得想个状态信号，使得每两次拍照都要重置累加用途得变量

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button takePhoto = (Button) findViewById(R.id.take_photo);
        Button chooseFromAlbum = (Button) findViewById(R.id.choose_from_album);
        picture = (ImageView) findViewById(R.id.picture);
        // ---
        mTxtValue1 = (TextView) findViewById(R.id.txt_value1);
        // 获取传感器管理器
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        takePhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                /**
                 * 首次，初始化代数因子
                 * 此外，获取代数，及 mDeltaDisp 置0
                 */
                initValues();

                // 创建 File 对象，用于存储由拍照得到的图片 output_image.jpg
                File outputImage = new File(getExternalCacheDir(), "output_image.jpg");

                try {
                    if (outputImage.exists()) {
                        outputImage.delete();
                    }
                    outputImage.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // 以时间戳命名拍照文件
                // todo


                if (Build.VERSION.SDK_INT >= 24) {
                    imageUri = FileProvider.getUriForFile(MainActivity.this,
                            "com.guzhuo.cameraalbumtest.fileprovider",
                            outputImage);
                } else {
                    imageUri = Uri.fromFile(outputImage);
                }

                // 启动相机程序
                Intent intent = new Intent("android.media.action.IMAGE_CAPTURE");

                // 拍照后的保存路径
                intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);

                // 启用快捷拍照，取消拍照后的确认预览
                intent.putExtra("android.intent.extra.quickCapture", true);
                startActivityForResult(intent, TAKE_PHOTO);

            }
        });

        chooseFromAlbum.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 点击 chooseFromAlbum 按钮，则动态申请 WRITE_EXTERNAL_STAROGE 权限，
                // 授予程序对 SD 卡读写的能力。
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                } else {
                    openAlbum();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 为加速度传感器注册监听器
        mSensorManager.registerListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                SensorManager.SENSOR_DELAY_GAME);
        // 为方向传感器注册监听器
        mSensorManager.registerListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION),  // 不建议的注册方式
                SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 若退出页面则注销监听器
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        // 如是，则表示现未进入计算状态
        if (mPointsDisp == null) return;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_LINEAR_ACCELERATION:
                double curTime = System.currentTimeMillis();  // 以微秒,记录当前时间
                mDeltaTime = (curTime - mPrevTime)/ 1000;  // 以秒,记录切片时间长度
                double mDeltaTime_Pow2 = mDeltaTime * mDeltaTime;

                // 应对 xyz 轴相关的代数运算
                for (int i = 0; i < event.values.length; i++) {
                    // 切片时间内的平均加速度
                    mDeltaAvgAcc[i] = (event.values[i] + mPrevAcc[i]) * 0.5;
                    // 切片时间内的位移量，视作匀加速运动
                    mDeltaDisp[i] = mAccVel[i] * mDeltaTime + 0.5 * mDeltaAvgAcc[i] * mDeltaTime_Pow2;

                    // 更新：本次切片时间内的平均速度，上一切片时间末的加速度
                    mAccVel[i] += mDeltaAvgAcc[i] * mDeltaTime;
                    mPrevAcc[i] = event.values[i];
                }
                Log.w(TAG, "onSensorChanged::mDeltaDisp[].model:" + Math.sqrt(mDeltaDisp[0] * mDeltaDisp[0] + mDeltaDisp[1] * mDeltaDisp[1] + mDeltaDisp[2] * mDeltaDisp[2]));

                // 更新时间
                mPrevTime = curTime;

                break;
            case Sensor.TYPE_ORIENTATION:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {

        switch (requestCode) {
            case TAKE_PHOTO:
                if (resultCode == RESULT_OK) {
                    try {
                        // 将图片插入到系统图库
                        // todo

                        // 将拍摄的照片显示出来
                        Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver()
                                .openInputStream(imageUri));
                        picture.setImageBitmap(bitmap);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }

                    // 广播通知图库刷新
                    // todo

                }

                break;
            case CHOOSE_PHOTO:
                if (resultCode == RESULT_OK) {
                    // 判断手机系统版本号
                    if (Build.VERSION.SDK_INT >= 19) {
                        // 4.4及以上系统使用此方法处理图片
                        handleImageOnKitKat(data);
                    } else {
                        // 4.4以下系统使用此方法处理图片
                        handleImageBeforeKitKat(data);
                    }
                }
                break;
            default:
                break;
        }
    }


    private void openAlbum() {
        Intent intent = new Intent("android.intent.action.GET_CONTENT");
        intent.setType("image/*");
        // 传回图片时，保证 onActivityResult() 进入 CHOOSE_PHOTO 的 case，以处理图片。
        startActivityForResult(intent, CHOOSE_PHOTO);  // 打开相册
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case 1:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openAlbum();
                } else {
                    Toast.makeText(this, "You denied the permission", Toast.LENGTH_SHORT).show();
                }
                break;
            default:
        }
    }

    @TargetApi(19)
    private void handleImageOnKitKat(Intent data) {
        String imagePath = null;
        Uri uri = data.getData();
        if (DocumentsContract.isDocumentUri(this, uri)) {
            // 若 document 类型的Uri，则通过 document id 处理
            String docId = DocumentsContract.getDocumentId(uri);
            // 若 Uri 的 authority 是 meida 格式，document id 则需再一次解析，
            if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                // 通过字符串分割的方法取出后半部分，才能得到真正的数字 id。
                String id = docId.split(":")[1];  // 解析数字格式的 id
                // 构建新的 Uri 和条件语句。
                String selection = MediaStore.Images.Media._ID + "=" + id;
                imagePath = getImagePath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection);
            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://download/public_downloads"), Long.valueOf(docId));
                imagePath = getImagePath(contentUri, null);
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            // 若 content 类型的 Uri，则使用普通方式处理
            imagePath = getImagePath(uri, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            // 若 file 类型的 Uri，直接获取图片路径
            imagePath = uri.getPath();
        }
        displayImage(imagePath);  // 根据图片路径显示图片
    }

    private void handleImageBeforeKitKat(Intent data) {
        Uri uri = data.getData();
        String imagePath = getImagePath(uri, null);
        displayImage(imagePath);
    }

    private String getImagePath(Uri uri, String selection) {
        String path = null;
        // 通过 Uri 和 selection 来获取图片的实际路径
        Cursor cursor = getContentResolver().query(uri, null, selection, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
            }
            cursor.close();
        }
        return path;
    }

    private void displayImage(String imagePath) {
        if (imagePath != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
            picture.setImageBitmap(bitmap);
        } else {
            Toast.makeText(this, "[MainActivity-displayImage()]failed to get image", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 若在回调函数中，则用于判断，若为首次拍照，则初始化相关变量/ 代数因子
     */
    private void initValues() {
        // GO!
        if (mPointsDisp == null) {
            // 首次拍照，初始化代数因子
            mPrevTime = System.currentTimeMillis();
            mAccVel = new double[3];
            mDeltaDisp = new double[3];
            mPointsDisp = new ArrayList<>();
        }else {
            fetchValues();
            // 将位移量置0
            mDeltaDisp = new double[3];
        }

        // 每拍两次就将当前速度预先置0，阻断干扰
        if (mTrigger_ResetAcc == 0) {
            mTrigger_ResetAcc = 2;
            mAccVel= new double[3];
        }else {
            mTrigger_ResetAcc--;
        }
    }


    /**
     * 将本次测距结果加入 mPointsDisp
     */
    private void fetchValues() {
        mPointsDisp.add(Math.sqrt(mDeltaDisp[0] * mDeltaDisp[0] + mDeltaDisp[1] * mDeltaDisp[1] + mDeltaDisp[2] * mDeltaDisp[2]));
        mTxtValue1.setText(mPointsDisp
                .get(mPointsDisp.size()-1)
                .toString());

        Log.w(TAG, "fetchValues: mAccVel: " + (mAccVel[0] * mAccVel[0] + mAccVel[1] * mAccVel[1] + mAccVel[2] * mAccVel[2]));
        Log.w(TAG, "fetchValues: mPointsDisp.size(" + mPointsDisp.size() + "), " + "mPointsDisp: " + mPointsDisp);

    }


}