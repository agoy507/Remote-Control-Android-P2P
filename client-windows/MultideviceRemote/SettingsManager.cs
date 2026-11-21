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
                if (string.IsNullOrWhiteSpace(settings.MyDeviceId) || DeviceIdUtil.IsLegacyPcHexId(settings.MyDeviceId))
                {
                    settings.MyDeviceId = AppSettings.GenerateDeviceId();
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
