using Microsoft.UI.Xaml;
using Microsoft.Extensions.Logging;

namespace MultideviceRemote;

public partial class App : Application
{
    public static ILoggerFactory LoggerFactory { get; private set; } = null!;
    public static MainWindow? MainWindow { get; private set; }

    public App()
    {
        LoggerFactory = Microsoft.Extensions.Logging.LoggerFactory.Create(builder =>
        {
            builder.AddConsole();
            builder.SetMinimumLevel(LogLevel.Debug);
        });

        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        MainWindow = new MainWindow();
        MainWindow.Activate();
    }
}
