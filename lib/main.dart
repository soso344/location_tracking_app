import 'dart:async';
import 'dart:developer';
import 'package:flutter/material.dart';
import 'package:get/get_navigation/get_navigation.dart';
import 'package:location/location.dart' as l;
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/services.dart'; // <--- THIS LINE IS NOW CORRECT

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return const GetMaterialApp(
      themeMode: ThemeMode.system,
      debugShowCheckedModeBanner: false,
      home: HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({Key? key}) : super(key: key);

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  bool gpsEnabled = false;
  bool backgroundPermissionGranted = false;
  l.Location location = l.Location();
  
  StreamSubscription? _locationSubscription;
  List<l.LocationData> locations = [];
  bool isForegroundTrackingActive = false;

  // Method Channels
  static const backgroundPlatform = MethodChannel('com.example.location_tracking_app/background');

  @override
  void initState() {
    super.initState();
    checkStatus();
  }

  @override
  void dispose() {
    _locationSubscription?.cancel();
    super.dispose();
  }

  // --- Permission and Status Check Logic ---
  Future<void> checkStatus() async {
    final isGps = await location.serviceEnabled();
    final isPermitted = await Permission.locationAlways.isGranted;
    setState(() {
      gpsEnabled = isGps;
      backgroundPermissionGranted = isPermitted;
    });
  }
  
  Future<void> requestEnableGps() async {
    if (!gpsEnabled) {
      bool isGpsActive = await location.requestService();
      if (isGpsActive) {
        setState(() => gpsEnabled = true);
      }
    }
  }

  Future<void> requestLocationPermission() async {
    final status = await Permission.locationAlways.request();
    setState(() {
      backgroundPermissionGranted = status == PermissionStatus.granted;
    });
    if (status.isPermanentlyDenied) {
        openAppSettings();
    }
  }

  // --- Background Task Logic ---
  Future<void> startBackgroundTracking() async {
    if (!gpsEnabled || !backgroundPermissionGranted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please enable GPS and grant "Allow all the time" location permission.')),
      );
      return;
    }
    try {
        await backgroundPlatform.invokeMethod('startPeriodicDataUpload');
        log("Started periodic background data upload.");
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Background tracking started! Data will be sent every ~15 minutes.')),
        );
    } on PlatformException catch(e) {
        log("Failed to start background tracking: ${e.message}");
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Error: ${e.message}')),
        );
    }
  }

  Future<void> stopBackgroundTracking() async {
    try {
        await backgroundPlatform.invokeMethod('stopPeriodicDataUpload');
        log("Stopped periodic background data upload.");
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Background tracking stopped!')),
        );
    } on PlatformException catch(e) {
        log("Failed to stop background tracking: ${e.message}");
    }
  }

  // --- Foreground (UI) Location Logic ---
  void startForegroundTracking() {
      if(isForegroundTrackingActive) return;
      _locationSubscription = location.onLocationChanged.listen((l.LocationData currentLocation) {
          setState(() {
              locations.insert(0, currentLocation);
          });
      });
      setState(() => isForegroundTrackingActive = true);
  }

  void stopForegroundTracking() {
      _locationSubscription?.cancel();
      setState(() {
          isForegroundTrackingActive = false;
          locations.clear();
      });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Device Tracker'),
        centerTitle: true,
      ),
      body: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Column(
          children: [
            buildListTile(
              "GPS",
              gpsEnabled
                  ? const Text("Enabled", style: TextStyle(color: Colors.green))
                  : ElevatedButton(
                      onPressed: requestEnableGps,
                      child: const Text("Enable GPS")),
            ),
            buildListTile(
              "Background Permission",
              backgroundPermissionGranted
                  ? const Text("Granted", style: TextStyle(color: Colors.green))
                  : ElevatedButton(
                      onPressed: requestLocationPermission,
                      child: const Text("Request Permission")),
            ),
            const Divider(),
            const Padding(
              padding: EdgeInsets.all(8.0),
              child: Text(
                  "This sends data every ~15 mins, even if the app is closed. This uses the device's battery.",
                   textAlign: TextAlign.center,
                   style: TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton.icon(
                  icon: const Icon(Icons.play_arrow),
                  label: const Text("Start Background Tracking"),
                  onPressed: startBackgroundTracking,
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
                ),
                ElevatedButton.icon(
                  icon: const Icon(Icons.stop),
                  label: const Text("Stop"),
                  onPressed: stopBackgroundTracking,
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                ),
              ],
            ),
            const Divider(),
            ListTile(
              title: const Text("Show Live Location in App"),
              trailing: Switch(
                value: isForegroundTrackingActive,
                onChanged: (val) {
                  if (val) {
                    startForegroundTracking();
                  } else {
                    stopForegroundTracking();
                  }
                },
              ),
            ),
            Expanded(
              child: ListView.builder(
                itemCount: locations.length,
                itemBuilder: (context, index) {
                  return ListTile(
                    dense: true,
                    title: Text(
                      "${locations[index].latitude?.toStringAsFixed(5)}, ${locations[index].longitude?.toStringAsFixed(5)}"),
                    subtitle: Text(DateTime.fromMillisecondsSinceEpoch(locations[index].time!.toInt()).toIso8601String()),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  ListTile buildListTile(String title, Widget? trailing) {
    return ListTile(
      dense: true,
      title: Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
      trailing: trailing,
    );
  }
}
