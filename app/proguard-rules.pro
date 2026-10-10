# AMS Manager has no reflection-heavy app code.
# Keep Room's generated database implementation and entities discoverable if a
# future Room/plugin version requires reflective access.
-keep class com.ams.mgr.data.AppDatabase_Impl { *; }

# Keep enum/string values used by the Room schema stable.
-keep class com.ams.mgr.data.Student { *; }
-keep class com.ams.mgr.data.Attendance { *; }
