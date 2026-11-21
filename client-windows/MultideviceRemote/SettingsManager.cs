using System.Text.Json;
using MultideviceRemote.Model;

namespace MultideviceRemote;

/// <summary>
/// Simpan/load AppSettings ke JSON file di AppData\Local\MultideviceRemote.
/// </summary>
public static class SettingsManager
{
    private static readonly string SettingsDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                     "MultideviceRemote");

    private static readonly string SettingsFile = Path.Combine(SettingsDir, "settings.json");

    private static readonly JsonSerializerOptions _opts = new() { WriteIndented = true };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(SettingsFile))
            {
                var json = File.ReadAllText(SettingsFile);
                var settings = JsonSerializer.Deserialize<AppSettings>(json) ?? new AppSettings();
                var dirty = false;
                if (string.IsNullOrWhiteSpace(settings.MyDeviceId) || DeviceIdUtil.IsLegacyPcHexId(settings.MyDeviceId))
                {
                    settings.MyDeviceId = AppSettings.GenerateDeviceId();
                    dirty = true;
                }
                if (string.IsNullOrWhiteSpace(settings.HostSessionPassword) || settings.HostSessionPassword.Trim().Length < 4)
                {
                    settings.HostSessionPassword = AppSettings.GenerateHostPassword();
                    dirty = true;
                }
                if (string.IsNullOrWhiteSpace(settings.SignalingUrl))
                {
                    settings.SignalingUrl = "wss://remote.agoy507.my.id/ws";
                    dirty = true;
                }
                if (dirty)
                {
                    Save(settings);
                }
                return settings;
            }
        }
        catch { /* fallback ke default */ }

        var created = new AppSettings();
        Save(created);
        return created;
    }

    public static void Save(AppSettings settings)
    {
        try
        {
            Directory.CreateDirectory(SettingsDir);
            var json = JsonSerializer.Serialize(settings, _opts);
            File.WriteAllText(SettingsFile, json);
        }
        catch { /* silent — settings tidak kritis */ }
    }
}
