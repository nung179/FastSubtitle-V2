package com.fastsubtitle.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.*
import android.media.projection.*
import android.os.*
import android.view.*
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class SubtitleService:Service(){
 companion object{
  const val EXTRA_RESULT_CODE="result_code";const val EXTRA_RESULT_DATA="result_data";const val EXTRA_API_KEY="api_key";const val EXTRA_LANGUAGE="language"
  const val EXTRA_TEXT_COLOR="text_color";const val EXTRA_TEXT_SIZE="text_size";const val EXTRA_BG_ALPHA="bg_alpha";const val ACTION_STOP="stop"
 }
 private var projection:MediaProjection?=null;private var rec:AudioRecord?=null;private var ws:WebSocket?=null;private var tr:Translator?=null
 private var run=false;private lateinit var wm:WindowManager;private var box:TextView?=null;private var lp:WindowManager.LayoutParams?=null
 private var apiKey="";private var language="zh";private var color="yellow";private var sp=25f;private var alpha=178
 private var lastTranslate=0L;private val seq=AtomicLong(0);private val pref by lazy{getSharedPreferences("fast_subtitle",MODE_PRIVATE)}
 private val client=OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(15,TimeUnit.SECONDS).build()
 override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("fs","Fast Subtitle",NotificationManager.IMPORTANCE_LOW))}
 override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{
  if(i?.action==ACTION_STOP){closeAll();stopSelf();return START_NOT_STICKY};if(run)return START_STICKY
  apiKey=i?.getStringExtra(EXTRA_API_KEY)?.trim().orEmpty();language=i?.getStringExtra(EXTRA_LANGUAGE)?:"zh";color=i?.getStringExtra(EXTRA_TEXT_COLOR)?:"yellow";sp=i?.getFloatExtra(EXTRA_TEXT_SIZE,25f)?:25f;alpha=i?.getIntExtra(EXTRA_BG_ALPHA,178)?:178
  val n=NotificationCompat.Builder(this,"fs").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Fast Subtitle").setContentText("Subtitle aktif").setOngoing(true).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(179,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(179,n)
  val rc=i?.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED
  @Suppress("DEPRECATION") val data=i?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
  if(rc!=Activity.RESULT_OK||data==null){stopSelf();return START_NOT_STICKY}
  overlay();translator()
  projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(rc,data)
  projection?.registerCallback(object:MediaProjection.Callback(){override fun onStop(){closeAll();stopSelf()}},null)
  deepgram();return START_STICKY
 }
 private fun translator(){
  val src=when(language){"en"->TranslateLanguage.ENGLISH;"ja"->TranslateLanguage.JAPANESE;"ko"->TranslateLanguage.KOREAN;else->TranslateLanguage.CHINESE}
  tr=Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(TranslateLanguage.INDONESIAN).build())
  show("Menyiapkan terjemahan…");tr?.downloadModelIfNeeded()?.addOnSuccessListener{show("Mendengarkan…")}?.addOnFailureListener{show("Model terjemahan gagal diunduh.")}
 }
 private fun deepgram(){
  val url="wss://api.deepgram.com/v1/listen?model=nova-3&language=$language&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&smart_format=true&punctuate=true&endpointing=300&utterance_end_ms=1000"
  ws=client.newWebSocket(Request.Builder().url(url).header("Authorization","Token $apiKey").build(),object:WebSocketListener(){
   override fun onOpen(w:WebSocket,r:Response){audio()}
   override fun onMessage(w:WebSocket,t:String){result(t)}
   override fun onFailure(w:WebSocket,e:Throwable,r:Response?){show("Koneksi subtitle terputus.")}
  })
 }
 private fun audio(){
  if(ActivityCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){show("Izin audio tidak tersedia.");return}
  val p=projection?:return
  val cfg=AudioPlaybackCaptureConfiguration.Builder(p).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
  val fmt=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
  val bs=maxOf(AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2,4096)
  rec=AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bs).setAudioPlaybackCaptureConfig(cfg).build();rec?.startRecording();run=true
  thread{name="FastSubtitleAudio";val b=ByteArray(bs);while(run){val n=rec?.read(b,0,b.size,AudioRecord.READ_BLOCKING)?:break;if(n>0)ws?.send(ByteString.of(b,0,n))}}
 }
 private fun result(j:String){try{
  val o=JSONObject(j);if(o.optString("type")!="Results")return
  val a=o.optJSONObject("channel")?.optJSONArray("alternatives")?:return;if(a.length()==0)return
  val s=a.getJSONObject(0).optString("transcript").trim();if(s.isBlank())return
  val final=o.optBoolean("is_final",false);val now=System.currentTimeMillis()
  if(!final&&now-lastTranslate<250)return;lastTranslate=now;translate(s)
 }catch(_:Exception){}}
 private fun translate(s:String){val q=seq.incrementAndGet();tr?.translate(s)?.addOnSuccessListener{x->if(q==seq.get()&&x.isNotBlank())show(x.trim())}}
 private fun overlay(){
  wm=getSystemService(WINDOW_SERVICE) as WindowManager
  box=TextView(this).apply{text="Mendengarkan…";gravity=Gravity.CENTER;setTextColor(if(color=="white")Color.WHITE else Color.rgb(255,221,0));textSize=sp;setShadowLayer(5f,2f,2f,Color.BLACK);setPadding(dp(18),dp(10),dp(18),dp(10));maxLines=3;background=GradientDrawable().apply{setColor(Color.argb(alpha,0,0,0));cornerRadius=dp(12).toFloat()}}
  lp=WindowManager.LayoutParams((resources.displayMetrics.widthPixels*.90f).toInt(),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;x=pref.getInt("x",0);y=pref.getInt("y",dp(300))}
  var dx=0f;var dy=0f;var sx=0;var sy=0
  box?.setOnTouchListener{_,e->val p=lp?:return@setOnTouchListener false;when(e.action){MotionEvent.ACTION_DOWN->{dx=e.rawX;dy=e.rawY;sx=p.x;sy=p.y;true};MotionEvent.ACTION_MOVE->{p.x=sx+(e.rawX-dx).toInt();p.y=sy+(e.rawY-dy).toInt();wm.updateViewLayout(box,p);true};MotionEvent.ACTION_UP->{pref.edit().putInt("x",p.x).putInt("y",p.y).apply();true};else->false}}
  wm.addView(box,lp)
 }
 private fun show(s:String){box?.post{if(s.isNotBlank())box?.text=s}}
 private fun closeAll(){run=false;try{rec?.stop()}catch(_:Exception){};rec?.release();rec=null;try{ws?.close(1000,"stop")}catch(_:Exception){};ws=null;tr?.close();tr=null;try{projection?.stop()}catch(_:Exception){};projection=null;box?.let{try{wm.removeView(it)}catch(_:Exception){}};box=null;stopForeground(STOP_FOREGROUND_REMOVE)}
 override fun onDestroy(){closeAll();super.onDestroy()}
 override fun onBind(i:Intent?):IBinder?=null
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
}
