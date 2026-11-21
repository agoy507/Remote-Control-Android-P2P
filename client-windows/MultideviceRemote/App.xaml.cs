using Microsoft.UI.Xaml;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace MultideviceRemote;

public partial class App : Application
{
    public static ILoggerFactory LoggerFactory { get; private set; } = NullLoggerFactory.Instance;
    public static MainWindow? MainWindow { get; private set; }

    public App()
    {
        UnhandledException += (_, e) => LogCrash("WinUI.UnhandledException", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            LogCrash("AppDomain.UnhandledException", e.ExceptionObject as Exception ?? new Exception(e.ExceptionObject?.ToString()));
        TaskScheduler.UnobservedTaskException += (_, e) => LogCrash("TaskScheduler.UnobservedTaskException", e.Exception);

        LoggerFactory = Microsoft.Extensions.Logging.LoggerFactory.Create(builder =>
        {
            builder.AddConsole();
            builder.SetMinimumLevel(LogLevel.Debug);
        });

        InitializeComponent();
    }

    private static void LogCrash(string source, Exception? ex)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MultideviceRemote");
            Directory.CreateDirectory(dir);
            var logPath = Path.Combine(dir, "crash.log");
            var entry = $"[{DateTimeOffset.Now:O}] [{source}] {ex?.Message}{Environment.NewLine}{ex}{Environment.NewLine}{new string('-', 60)}{Environment.NewLine}";
            File.AppendAllText(logPath, entry);
        }
        catch
        {
            // Ignore logging errors during crash handling.
        }
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        MainWindow = new MainWindow();
        MainWindow.Activate();
    }
}
