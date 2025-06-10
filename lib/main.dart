// lib/main.dart
import 'dart:async';
import 'dart:developer';
import 'package:flutter/material.dart';
import 'package:get/get.dart'; // <-- CORRECTED IMPORT
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart'; // <-- THIS IMPORT WILL NOW WORK

// Data model for notifications received from Kotlin
class StoredNotification {
  final int id;
  final String packageName;
  final String title;
  final String text;
  final DateTime timestamp;

  StoredNotification({
    required this.id,
    required this.packageName,
    required this.title,
    required this.text,
    required this.timestamp,
  });

  factory StoredNotification.fromMap(Map<dynamic, dynamic> map) {
    return StoredNotification(
      id: map['id'],
      packageName: map['packageName'] ?? '',
      title: map['title'] ?? '',
      text: map['text'] ?? '',
      timestamp: DateTime.fromMillisecondsSinceEpoch(map['timestamp']),
    );
  }
}

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    // This will now work because of the corrected import
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

  final TextEditingController _deviceNameController = TextEditingController();
  List<StoredNotification> _storedNotifications = [];
  bool _isLoadingNotifications = false;

  static const backgroundPlatform = MethodChannel('com.example.location_tracking_app/background');
  static const notificationPlatform = MethodChannel('com.example.location_tracking_app/notifications');
  static const dataPlatform = MethodChannel('com.example.location_tracking_app/data');

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    checkAllStatuses();
    _getDeviceName();
    _fetchStoredNotifications();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _deviceNameController.dispose();
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
    // Using GetX for GPS check, which is simpler
    final isGps = await Get.isGpsEnable;
    final isPermitted = await Permission.locationAlways.isGranted;
    final isNotificationPermitted = await notificationPlatform.invokeMethod<bool>('checkNotificationPermission') ?? false;

    if (mounted) {
      setState(() {
        gpsEnabled = isGps;
        backgroundPermissionGranted = isPermitted;
        notificationAccessGranted = isNotificationPermitted;
      });
    }
  }

  Future<void> requestEnableGps() async {
    // Using GetX for GPS request
    await Get.requestGpsPermission();
    await checkAllStatuses();
  }

  Future<void> requestLocationPermission() async {
    final status = await Permission.locationAlways.request();
    if (status.isPermanentlyDenied) {
      openAppSettings();
    }
    await checkAllStatuses();
  }

  Future<void> requestNotificationPermission() async {
    await notificationPlatform.invokeMethod('requestNotificationPermission');
  }

  Future<void> _getDeviceName() async {
    try {
      final String name = await dataPlatform.invokeMethod('getDeviceName');
      _deviceNameController.text = name;
    } catch (e) {
      log("Failed to get device name: $e");
    }
  }

  Future<void> _setDeviceName() async {
    try {
      await dataPlatform.invokeMethod('setDeviceName', {'name': _deviceNameController.text});
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Device name saved!')));
      FocusScope.of(context).unfocus(); // Dismiss keyboard
    } catch (e) {
      log("Failed to set device name: $e");
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Error saving name: $e')));
    }
  }

  Future<void> _fetchStoredNotifications() async {
    setState(() => _isLoadingNotifications = true);
    try {
      final List<dynamic>? results = await dataPlatform.invokeMethod('getStoredNotifications');
      if (results != null) {
        final notifications = results.map((map) => StoredNotification.fromMap(map)).toList();
        notifications.sort((a, b) => b.timestamp.compareTo(a.timestamp));
        setState(() {
          _storedNotifications = notifications;
        });
      }
    } catch (e) {
      log("Failed to fetch notifications: $e");
    } finally {
      if (mounted) {
        setState(() => _isLoadingNotifications = false);
      }
    }
  }

  Future<void> startBackgroundTracking() async {
    if (!gpsEnabled || !backgroundPermissionGranted || !notificationAccessGranted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please enable all permissions before starting.')),
      );
      return;
    }
    await backgroundPlatform.invokeMethod('startPeriodicDataUpload');
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Background tracking started!')));
  }

  Future<void> stopBackgroundTracking() async {
    await backgroundPlatform.invokeMethod('stopPeriodicDataUpload');
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Background tracking stopped!')));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Device Tracker Settings'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // --- Device Name Section ---
            Text('Device Identifier', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            TextField(
              controller: _deviceNameController,
              decoration: const InputDecoration(
                labelText: 'Enter a name for this device',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            ElevatedButton.icon(
              icon: const Icon(Icons.save),
              label: const Text('Save Name'),
              onPressed: _setDeviceName,
            ),
            const Divider(height: 32),

            // --- Permissions Section ---
            Text('Required Permissions', style: Theme.of(context).textTheme.titleLarge),
            buildListTile(
              "GPS / Location Service",
              gpsEnabled
                  ? const Text("Enabled", style: TextStyle(color: Colors.green))
                  : ElevatedButton(onPressed: requestEnableGps, child: const Text("Enable")),
            ),
            buildListTile(
              "Background Location",
              backgroundPermissionGranted
                  ? const Text("Granted", style: TextStyle(color: Colors.green))
                  : ElevatedButton(onPressed: requestLocationPermission, child: const Text("Grant")),
            ),
            buildListTile(
              "Notification Access",
              notificationAccessGranted
                  ? const Text("Granted", style: TextStyle(color: Colors.green))
                  : ElevatedButton(onPressed: requestNotificationPermission, child: const Text("Grant")),
            ),
            const Divider(height: 32),

            // --- Background Service Section ---
            Text('Background Service', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton.icon(
                  icon: const Icon(Icons.play_arrow),
                  label: const Text("Start Tracking"),
                  onPressed: startBackgroundTracking,
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.green),
                ),
                ElevatedButton.icon(
                  icon: const Icon(Icons.stop),
                  label: const Text("Stop Tracking"),
                  onPressed: stopBackgroundTracking,
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
                ),
              ],
            ),
            const Divider(height: 32),

            // --- Notification Viewer ---
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Stored Notifications', style: Theme.of(context).textTheme.titleLarge),
                IconButton(
                  icon: const Icon(Icons.refresh),
                  onPressed: _fetchStoredNotifications,
                ),
              ],
            ),
            const SizedBox(height: 8),
            _isLoadingNotifications
                ? const Center(child: CircularProgressIndicator())
                : _storedNotifications.isEmpty
                    ? const Center(
                        child: Text('No notifications stored in the database.', style: TextStyle(color: Colors.grey)),
                      )
                    : Container(
                        height: 300,
                        decoration: BoxDecoration(
                          border: Border.all(color: Colors.grey.shade300),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: ListView.builder(
                          itemCount: _storedNotifications.length,
                          itemBuilder: (context, index) {
                            final notif = _storedNotifications[index];
                            return ListTile(
                              dense: true,
                              title: Text(notif.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                              subtitle: Text(
                                "${notif.packageName}\n${notif.text}",
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                              ),
                              // This now works because of the intl package
                              trailing: Text(DateFormat('HH:mm').format(notif.timestamp)),
                              isThreeLine: true,
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
      contentPadding: EdgeInsets.zero,
      title: Text(title),
      trailing: trailing,
    );
  }
}
