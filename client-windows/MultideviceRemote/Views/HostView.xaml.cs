using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using MultideviceRemote.Model;
using MultideviceRemote.WebRtc;
using Windows.ApplicationModel.DataTransfer;

namespace MultideviceRemote.Views;

public sealed partial class HostView : UserControl
{
    private readonly HostSessionCoordinator _coordinator = new();
    private bool _isLoadingUi;
    private bool _dialogOpen;
    private bool _isBusyToggling;

    public bool IsHostRunning => _coordinator.IsHostRunning;
    public string CurrentDeviceIdFormatted => MyDeviceIdBox.Text;

    public event Action<bool>? HostRunningStateChanged;
    public event Action? IncomingApprovalRequested;

    public HostView()
    {
        InitializeComponent();
        WireCoordinatorEvents();
        LoadSettings();
    }

    public void ReloadSettings() => LoadSettings();

    public async Task StopHostIfRunningAsync()
    {
        if (_coordinator.IsHostRunning)
        {
            await _coordinator.StopHostAsync();
            UpdateHostButtonUi(isRunning: false, hasPeer: false);
            HostRunningStateChanged?.Invoke(false);
        }
    }

    public void StopForWindowClose()
    {
        _coordinator.ForceStopForExit();
    }

    private void WireCoordinatorEvents()
    {
        _coordinator.OnStatusChanged += status => DispatcherQueue.TryEnqueue(() =>
        {
            HostStatusBar.Message = status;
        });

        _coordinator.OnSignalingStateChanged += registered => DispatcherQueue.TryEnqueue(() =>
        {
            if (!_coordinator.IsHostRunning)
            {
                HostStatusBar.Severity = InfoBarSeverity.Informational;
            }
            else if (_coordinator.HasActivePeer)
            {
                HostStatusBar.Severity = InfoBarSeverity.Success;
            }
            else
            {
                HostStatusBar.Severity = registered ? InfoBarSeverity.Success : InfoBarSeverity.Warning;
            }
        });

        _coordinator.OnIncomingRequest += req => DispatcherQueue.TryEnqueue(() =>
        {
            _ = ShowIncomingRequestDialogAsync(req);
        });

        _coordinator.OnPeerConnected += (peerId, peerAlias, fingerprint) => DispatcherQueue.TryEnqueue(() =>
        {
            ErrorBar.IsOpen = false;
            HostStatusBar.Severity = InfoBarSeverity.Success;
            HostStatusBar.Message = $"Tersambung dengan {peerAlias} ({DeviceIdUtil.Format(peerId)}) • E2EE: {fingerprint}";
            DisconnectPeerButton.Visibility = Visibility.Visible;
        });

        _coordinator.OnPeerDisconnected += reason => DispatcherQueue.TryEnqueue(() =>
        {
            DisconnectPeerButton.Visibility = Visibility.Collapsed;
            HostStatusBar.Severity = _coordinator.IsHostRunning ? InfoBarSeverity.Success : InfoBarSeverity.Informational;
            if (!string.IsNullOrWhiteSpace(reason))
            {
                HostStatusBar.Message = _coordinator.IsHostRunning
                    ? $"Standby • Sesi sebelumnya berakhir: {reason}"
                    : "Mode Host nonaktif.";
            }
        });

        _coordinator.OnTrustedControllerRecorded += entry => DispatcherQueue.TryEnqueue(() =>
        {
            SaveTrustedController(entry);
        });

        _coordinator.OnError += err => DispatcherQueue.TryEnqueue(() =>
        {
            ErrorBar.Message = err;
            ErrorBar.IsOpen = true;
        });
    }

    private async Task ShowIncomingRequestDialogAsync(IncomingHostRequest req)
    {
        if (_dialogOpen || XamlRoot == null)
        {
            await _coordinator.RejectPendingAsync();
            return;
        }

        _dialogOpen = true;
        try
        {
            IncomingApprovalRequested?.Invoke();
            await Task.Delay(120);

            var dialog = new ContentDialog
            {
                XamlRoot = XamlRoot,
                Title = "Permintaan Kendali Masuk",
                Content =
                    $"Perangkat: {req.FromAlias}\n" +
                    $"ID: {DeviceIdUtil.Format(req.FromDeviceId)}\n" +
                    $"Sidik E2EE: {req.SafetyFingerprint}\n\n" +
                    "Izinkan perangkat ini melihat layar dan mengendalikan PC Anda?",
                PrimaryButtonText = "Izinkan",
                CloseButtonText = "Tolak",
                DefaultButton = ContentDialogButton.Primary
            };

            var result = await dialog.ShowAsync();
            if (result == ContentDialogResult.Primary)
            {
                await _coordinator.ApprovePendingAsync();
            }
            else
            {
                await _coordinator.RejectPendingAsync();
            }
        }
        finally
        {
            _dialogOpen = false;
        }
    }

    private void LoadSettings()
    {
        _isLoadingUi = true;
        try
        {
            var settings = SettingsManager.Load();
            MyDeviceIdBox.Text = DeviceIdUtil.Format(settings.MyDeviceId);
            HostPasswordBox.Password = settings.HostSessionPassword ?? "";
            RequireApprovalSwitch.IsOn = settings.RequireVisualApproval;
            AllowInputSwitch.IsOn = settings.AllowInputControl;
            AutoStartHostSwitch.IsOn = settings.AutoStartHostOnLaunch;
            AutoMinimizeHostSwitch.IsOn = settings.AutoMinimizeOnHostStart;
            SignalingUrlBox.Text = settings.SignalingUrl ?? "wss://remote.agoy507.my.id/ws";
            ApiTokenBox.Password = settings.ServerApiToken ?? "";

            var targetPreset = string.IsNullOrWhiteSpace(settings.HostQualityPreset) ? "SHARP_HD" : settings.HostQualityPreset;
            ComboBoxItem? match = null;
            foreach (var item in QualityComboBox.Items.OfType<ComboBoxItem>())
            {
                if (string.Equals(item.Tag as string, targetPreset, StringComparison.OrdinalIgnoreCase))
                {
                    match = item;
                    break;
                }
            }
            QualityComboBox.SelectedItem = match ?? QualityComboBox.Items.FirstOrDefault();

            RenderTrustedControllers(settings.TrustedControllers ?? new List<TrustedControllerEntry>());
        }
        finally
        {
            _isLoadingUi = false;
        }
    }

    private AppSettings SaveCurrentHostSettings()
    {
        var settings = SettingsManager.Load();
        settings.HostSessionPassword = HostPasswordBox.Password.Trim();
        settings.RequireVisualApproval = RequireApprovalSwitch.IsOn;
        settings.AllowInputControl = AllowInputSwitch.IsOn;
        settings.AutoStartHostOnLaunch = AutoStartHostSwitch.IsOn;
        settings.AutoMinimizeOnHostStart = AutoMinimizeHostSwitch.IsOn;
        settings.HostQualityPreset = (QualityComboBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "BALANCED";

        var url = SignalingUrlBox.Text.Trim();
        if (!string.IsNullOrEmpty(url))
        {
            settings.SignalingUrl = url;
        }
        settings.ServerApiToken = ApiTokenBox.Password.Trim();

        SettingsManager.Save(settings);
        return settings;
    }

    private void SaveTrustedController(TrustedControllerEntry entry)
    {
        var settings = SettingsManager.Load();
        var norm = DeviceIdUtil.Normalize(entry.DeviceId);
        settings.TrustedControllers.RemoveAll(x => DeviceIdUtil.Normalize(x.DeviceId) == norm);
        settings.TrustedControllers.Insert(0, entry);
        SettingsManager.Save(settings);

        RenderTrustedControllers(settings.TrustedControllers);
        PushOptionsToCoordinator(settings);
    }

    private static Microsoft.UI.Xaml.Media.Brush? TryGetBrush(string key) =>
        Application.Current.Resources.TryGetValue(key, out var val)
            ? val as Microsoft.UI.Xaml.Media.Brush
            : null;

    private void RenderTrustedControllers(List<TrustedControllerEntry> items)
    {
        TrustedDevicesPanel.Children.Clear();
        NoTrustedText.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        foreach (var item in items)
        {
            var row = new Border
            {
                Background = TryGetBrush("CardBackgroundFillColorDefaultBrush"),
                BorderBrush = TryGetBrush("CardStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(6),
                Padding = new Thickness(12, 8, 8, 8)
            };

            var grid = new Grid { ColumnSpacing = 8 };
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var infoPanel = new StackPanel { Spacing = 2 };
            infoPanel.Children.Add(new TextBlock
            {
                Text = string.IsNullOrWhiteSpace(item.Alias) ? DeviceIdUtil.Format(item.DeviceId) : $"{item.Alias} ({DeviceIdUtil.Format(item.DeviceId)})",
                TextTrimming = TextTrimming.CharacterEllipsis
            });
            var dt = DateTimeOffset.FromUnixTimeMilliseconds(item.LastConnectedEpochMs).LocalDateTime;
            infoPanel.Children.Add(new TextBlock
            {
                Text = $"Terakhir terhubung: {dt:dd/MM/yyyy HH:mm}",
                Foreground = TryGetBrush("TextFillColorSecondaryBrush"),
                FontSize = 12
            });

            var removeBtn = new Button
            {
                Content = "Hapus",
                Tag = item.DeviceId,
                VerticalAlignment = VerticalAlignment.Center
            };
            removeBtn.Click += RemoveTrustedButton_Click;

            Grid.SetColumn(infoPanel, 0);
            Grid.SetColumn(removeBtn, 1);
            grid.Children.Add(infoPanel);
            grid.Children.Add(removeBtn);
            row.Child = grid;

            TrustedDevicesPanel.Children.Add(row);
        }
    }

    private void PushOptionsToCoordinator(AppSettings settings)
    {
        if (!_coordinator.IsHostRunning) return;
        _coordinator.UpdateHostOptions(
            hostPassword: settings.HostSessionPassword,
            requireVisualApproval: settings.RequireVisualApproval,
            allowInputControl: settings.AllowInputControl,
            qualityPreset: settings.HostQualityPreset,
            trustedControllers: settings.TrustedControllers
        );
    }

    private void UpdateHostButtonUi(bool isRunning, bool hasPeer)
    {
        ToggleHostButton.Content = isRunning ? "Hentikan Mode Host" : "Aktifkan Mode Host";
        DisconnectPeerButton.Visibility = hasPeer ? Visibility.Visible : Visibility.Collapsed;
        SignalingUrlBox.IsEnabled = !isRunning;
        ApiTokenBox.IsEnabled = !isRunning;
    }

    public async Task<bool> StartHostIfNotRunningAsync(bool isAutoStart = false)
    {
        if (_coordinator.IsHostRunning) return true;
        if (_isBusyToggling) return false;

        _isBusyToggling = true;
        ErrorBar.IsOpen = false;
        ToggleHostButton.IsEnabled = false;

        try
        {
            var password = HostPasswordBox.Password.Trim();
            if (password.Length < 4)
            {
                if (isAutoStart)
                {
                    password = AppSettings.GenerateHostPassword();
                    _isLoadingUi = true;
                    HostPasswordBox.Password = password;
                    _isLoadingUi = false;
                }
                else
                {
                    ErrorBar.Message = "Kata sandi sesi Host minimal 4 karakter.";
                    ErrorBar.IsOpen = true;
                    return false;
                }
            }

            var url = SignalingUrlBox.Text.Trim();
            if (string.IsNullOrEmpty(url))
            {
                if (isAutoStart)
                {
                    url = "wss://remote.agoy507.my.id/ws";
                    SignalingUrlBox.Text = url;
                }
                else
                {
                    ErrorBar.Message = "URL signaling tidak boleh kosong.";
                    ErrorBar.IsOpen = true;
                    return false;
                }
            }

            var settings = SaveCurrentHostSettings();
            var myId = DeviceIdUtil.Normalize(settings.MyDeviceId);
            if (myId.Length < 6)
            {
                myId = AppSettings.GenerateDeviceId();
                settings.MyDeviceId = myId;
                SettingsManager.Save(settings);
                MyDeviceIdBox.Text = DeviceIdUtil.Format(myId);
            }
            var myAlias = string.IsNullOrWhiteSpace(settings.MyAlias)
                ? Environment.MachineName
                : settings.MyAlias;

            await _coordinator.StartHostAsync(
                signalingUrl: url,
                apiToken: settings.ServerApiToken,
                myDeviceId: myId,
                myAlias: myAlias,
                hostPassword: password,
                requireVisualApproval: settings.RequireVisualApproval,
                allowInputControl: settings.AllowInputControl,
                qualityPreset: settings.HostQualityPreset,
                trustedControllers: settings.TrustedControllers
            );

            UpdateHostButtonUi(isRunning: true, hasPeer: _coordinator.HasActivePeer);
            HostRunningStateChanged?.Invoke(true);
            return true;
        }
        catch (Exception ex)
        {
            ErrorBar.Message = $"Gagal mengaktifkan Mode Host: {ex.Message}";
            ErrorBar.IsOpen = true;
            UpdateHostButtonUi(isRunning: false, hasPeer: false);
            return false;
        }
        finally
        {
            ToggleHostButton.IsEnabled = true;
            _isBusyToggling = false;
        }
    }

    // ── Event Handlers ────────────────────────────────────────────────────────

    private async void ToggleHostButton_Click(object sender, RoutedEventArgs e)
    {
        if (_isBusyToggling) return;

        if (_coordinator.IsHostRunning)
        {
            _isBusyToggling = true;
            ErrorBar.IsOpen = false;
            ToggleHostButton.IsEnabled = false;
            try
            {
                await _coordinator.StopHostAsync();
                UpdateHostButtonUi(isRunning: false, hasPeer: false);
                HostStatusBar.Severity = InfoBarSeverity.Informational;
                HostStatusBar.Message = "Mode Host nonaktif.";
                HostRunningStateChanged?.Invoke(false);
            }
            catch (Exception ex)
            {
                ErrorBar.Message = $"Gagal menghentikan Mode Host: {ex.Message}";
                ErrorBar.IsOpen = true;
            }
            finally
            {
                ToggleHostButton.IsEnabled = true;
                _isBusyToggling = false;
            }
            return;
        }

        await StartHostIfNotRunningAsync(isAutoStart: false);
    }

    private async void DisconnectPeerButton_Click(object sender, RoutedEventArgs e)
    {
        await _coordinator.DisconnectActivePeerAsync();
        DisconnectPeerButton.Visibility = Visibility.Collapsed;
    }

    private void CopyIdButton_Click(object sender, RoutedEventArgs e)
    {
        CopyToClipboard(MyDeviceIdBox.Text);
        HostStatusBar.Message = "Device ID PC disalin ke clipboard.";
    }

    private void CopyPasswordButton_Click(object sender, RoutedEventArgs e)
    {
        CopyToClipboard(HostPasswordBox.Password);
        HostStatusBar.Message = "Kata sandi Host disalin ke clipboard.";
    }

    private void RandomPasswordButton_Click(object sender, RoutedEventArgs e)
    {
        HostPasswordBox.Password = AppSettings.GenerateHostPassword();
        var settings = SaveCurrentHostSettings();
        PushOptionsToCoordinator(settings);
    }

    private void ShowPasswordCheckBox_Changed(object sender, RoutedEventArgs e)
    {
        HostPasswordBox.PasswordRevealMode = ShowPasswordCheckBox.IsChecked == true
            ? PasswordRevealMode.Visible
            : PasswordRevealMode.Hidden;
    }

    private void HostPasswordBox_PasswordChanged(object sender, RoutedEventArgs e)
    {
        if (_isLoadingUi) return;
        var settings = SaveCurrentHostSettings();
        PushOptionsToCoordinator(settings);
    }

    private void HostOption_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoadingUi) return;
        var settings = SaveCurrentHostSettings();
        PushOptionsToCoordinator(settings);
    }

    private void QualityComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_isLoadingUi) return;
        var settings = SaveCurrentHostSettings();
        PushOptionsToCoordinator(settings);
    }

    private void RemoveTrustedButton_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string id }) return;

        var settings = SettingsManager.Load();
        var norm = DeviceIdUtil.Normalize(id);
        settings.TrustedControllers.RemoveAll(x => DeviceIdUtil.Normalize(x.DeviceId) == norm);
        SettingsManager.Save(settings);

        RenderTrustedControllers(settings.TrustedControllers);
        PushOptionsToCoordinator(settings);
    }

    private static void CopyToClipboard(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        var pkg = new DataPackage();
        pkg.SetText(text);
        Clipboard.SetContent(pkg);
    }
}
