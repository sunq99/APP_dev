package com.example.hello;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {
    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationClient;
    private final List<LatLng> markerPositions = new ArrayList<>();
    private final OkHttpClient client = UnsafeOkHttpClient.getUnsafeOkHttpClient();
    private final Map<LatLng, Boolean> trafficLightCache = new HashMap<>();
    private final long CACHE_EXPIRY_DURATION = 5 * 1000; // 5초 캐시 유지
    private final Map<LatLng, Long> cacheTimestamps = new HashMap<>();
    private final Map<LatLng, Marker> markersMap = new HashMap<>();
    private final Set<LatLng> ongoingRequests = new HashSet<>(); // 중복 요청 방지용

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_map);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map_container);
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.map_container, mapFragment)
                    .commit();
        }
        mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;

        // 지도 UI 설정
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.getUiSettings().setMyLocationButtonEnabled(true);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(false);
        mMap.getUiSettings().setRotateGesturesEnabled(false);

        // 위치 사용 권한 확인 후 현위치 기능 활성화
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
            requestCurrentLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }

        // 카메라 변경 리스너 추가
        mMap.setOnCameraIdleListener(this::addMarkersWithinVisibleRegion);

        // CSV 파일에서 데이터 로드
        loadMarkersFromCsv();
    }

    private void loadMarkersFromCsv() {
        new Thread(() -> {
            try {
                InputStream inputStream = getAssets().open("translated_coordinates.csv");
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "cp949"));
                String line;

                // 첫 번째 줄 (헤더) 건너뛰기
                reader.readLine();

                int lineNumber = 1;
                List<LatLng> tempMarkerPositions = new ArrayList<>();
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String[] tokens = line.split(",");
                    if (tokens.length >= 4) {
                        String longitudeStr = tokens[3].trim(); // X좌표 (경도)
                        String latitudeStr = tokens[2].trim(); // Y좌표 (위도)

                        if (isPureNumeric(latitudeStr) && isPureNumeric(longitudeStr)) {
                            try {
                                double latitude = Double.parseDouble(latitudeStr);
                                double longitude = Double.parseDouble(longitudeStr);

                                if (latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180) {
                                    LatLng location = new LatLng(latitude, longitude);
                                    tempMarkerPositions.add(location);
                                }
                            } catch (NumberFormatException e) {
                                Log.e("MapActivity", "Skipping invalid latitude or longitude at line " + lineNumber + ": " + latitudeStr + ", " + longitudeStr);
                            }
                        } else {
                            Log.e("MapActivity", "Non-numeric latitude/longitude at line " + lineNumber + ": " + latitudeStr + ", " + longitudeStr);
                        }
                    } else {
                        Log.e("MapActivity", "Invalid line format at line " + lineNumber + ": " + line);
                    }
                }

                reader.close();

                synchronized (markerPositions) {
                    markerPositions.clear();
                    markerPositions.addAll(tempMarkerPositions);
                }

                runOnUiThread(this::addMarkersWithinVisibleRegion);

            } catch (IOException e) {
                Log.e("MapActivity", "Error reading CSV file", e);
            }
        }).start();
    }

    private void addMarkersWithinVisibleRegion() {
        if (mMap == null) return;

        runOnUiThread(() -> {
            LatLngBounds bounds = mMap.getProjection().getVisibleRegion().latLngBounds;

            List<LatLng> markerPositionsCopy;
            synchronized (markerPositions) {
                markerPositionsCopy = new ArrayList<>(markerPositions);
            }

            for (LatLng position : markerPositionsCopy) {
                if (bounds.contains(position)) {
                    getTrafficLightStatus(position, (isGreenLight) -> {
                        runOnUiThread(() -> {
                            if (markersMap.containsKey(position)) {
                                markersMap.get(position).remove(); // 기존 마커 제거
                            }

                            MarkerOptions markerOptions = new MarkerOptions()
                                    .position(position)
                                    .title("Intersection Code: " + position.toString());

                            if (isGreenLight) {
                                markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
                            } else {
                                markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
                            }

                            Marker marker = mMap.addMarker(markerOptions);
                            markersMap.put(position, marker); // 새 마커 추가
                        });
                    });
                }
            }
        });
    }

    private void getTrafficLightStatus(LatLng position, TrafficLightCallback callback) {
        // 중복 요청 방지: 요청 중인 위치라면 요청하지 않음
        if (ongoingRequests.contains(position)) {
            Log.d("TrafficLightStatus", "Skipping duplicate request for position: " + position);
            return;
        }

        // 요청 중인 위치로 추가
        ongoingRequests.add(position);

        // 캐시된 데이터 확인
        if (trafficLightCache.containsKey(position) && cacheTimestamps.containsKey(position)) {
            long currentTime = System.currentTimeMillis();
            long cachedTime = cacheTimestamps.get(position);
            if (currentTime - cachedTime < CACHE_EXPIRY_DURATION) {
                // 캐시된 데이터가 유효하면 반환
                callback.onResult(trafficLightCache.get(position));
                ongoingRequests.remove(position); // 요청 완료로 처리
                return;
            }
        }

        String apiKey = "*******API-KEY*****";
        String url = "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/v2xSignalPhaseTimingInformation/1.0?apikey=" + apiKey;

        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("MapActivity", "API call failed: " + e.getMessage());
                runOnUiThread(() -> callback.onResult(false)); // 기본적으로 적색 신호
                ongoingRequests.remove(position); // 요청 완료로 처리
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseData = response.body().string();
                    Log.d("API Response", responseData); // API 응답 로그 출력
                    boolean isGreenLight = parseTrafficLightStatus(responseData);

                    // 캐시에 저장
                    trafficLightCache.put(position, isGreenLight);
                    cacheTimestamps.put(position, System.currentTimeMillis());

                    runOnUiThread(() -> callback.onResult(isGreenLight));
                } else {
                    Log.e("MapActivity", "API call failed with response code: " + response.code());
                    runOnUiThread(() -> callback.onResult(false)); // 기본적으로 적색 신호
                }
                ongoingRequests.remove(position); // 요청 완료로 처리
            }
        });
    }

    private boolean parseTrafficLightStatus(String responseData) {
        Log.d("TrafficLightStatus", "Parsing response: " + responseData); // 응답 데이터 확인용 로그
        return Math.random() > 0.5; // 임의의 논리 (실제 API 응답을 바탕으로 파싱 로직을 구현해야 함)
    }

    private boolean isPureNumeric(String str) {
        return str.matches("-?\\d+(\\.\\d+)?");
    }

    private void requestCurrentLocationUpdates() {
        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(10000); // 업데이트 간격 (밀리초)
        locationRequest.setFastestInterval(5000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        LocationCallback locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    LatLng currentLocation = new LatLng(location.getLatitude(), location.getLongitude());
                    mMap.addMarker(new MarkerOptions()
                            .position(currentLocation)
                            .title("Current Location")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation, 18.5f));
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    mMap.setMyLocationEnabled(true);
                    requestCurrentLocationUpdates();
                }
            }
        }
    }
}

interface TrafficLightCallback {
    void onResult(boolean isGreenLight);
}

class UnsafeOkHttpClient {
    public static OkHttpClient getUnsafeOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
            builder.connectTimeout(30, TimeUnit.SECONDS);
            builder.readTimeout(30, TimeUnit.SECONDS);

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
