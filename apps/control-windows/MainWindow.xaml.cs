using System.Collections.ObjectModel;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;

namespace SkySpy.Control;

public partial class MainWindow : Window
{
    private readonly DispatcherTimer _refreshTimer = new()
    {
        Interval = TimeSpan.FromSeconds(6)
    };

    private SkySpyApiHost? _apiHost;
    private bool _busy;

    public ObservableCollection<DeviceRow> Devices { get; } = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = this;
        _refreshTimer.Tick += async (_, _) => await RefreshDevicesAsync();
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        UpdateLanAddress();
        try
        {
            _apiHost = await SkySpyApiHost.StartAsync();
            SetServiceState(true, "Local service running");
            StatusText.Text = "SkySpy is ready. This native app manages the local connection service.";
            await RefreshDevicesAsync();
            _refreshTimer.Start();
        }
        catch (Exception ex)
        {
            SetServiceState(false, "Local service unavailable");
            StatusText.Text = "Could not start the local service: " + ex.Message;
            MessageBox.Show(
                "SkySpy could not start its local device service on port 8787. Close any other application using that port and try again.\n\n" + ex.Message,
                "SkySpy Control",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }
    }

    private async Task RefreshDevicesAsync()
    {
        if (_busy || _apiHost is null)
        {
            return;
        }

        _busy = true;
        try
        {
            var devices = await _apiHost.Store.GetAllAsync();
            Devices.Clear();

            foreach (DeviceRecord device in devices.OrderByDescending(item => item.LastSeenAt))
            {
                Devices.Add(new DeviceRow(device));
            }

            int online = Devices.Count(device => device.Status == "Online");
            TotalCountText.Text = Devices.Count.ToString();
            OnlineCountText.Text = online.ToString();
            OfflineCountText.Text = (Devices.Count - online).ToString();
            StatusText.Text = "Last refreshed " + DateTime.Now.ToString("HH:mm:ss") + ".";
        }
        catch (Exception ex)
        {
            StatusText.Text = "Could not read the local device inventory: " + ex.Message;
        }
        finally
        {
            _busy = false;
        }
    }

    private void UpdateLanAddress()
    {
        IPAddress? address = NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(item => item.Address)
            .FirstOrDefault(IsPrivateIPv4);

        LanAddressText.Text = address is null
            ? "No private IPv4 address detected"
            : "http://" + address + ":8787";
    }

    private static bool IsPrivateIPv4(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(address))
        {
            return false;
        }

        byte[] bytes = address.GetAddressBytes();
        return bytes[0] == 10
            || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31)
            || (bytes[0] == 192 && bytes[1] == 168);
    }

    private void SetServiceState(bool running, string label)
    {
        ServiceDot.Fill = new SolidColorBrush(
            running ? Color.FromRgb(22, 163, 74) : Color.FromRgb(220, 38, 38));
        ServiceStateText.Text = label;
        ServiceStateText.Foreground = new SolidColorBrush(
            running ? Color.FromRgb(22, 101, 52) : Color.FromRgb(185, 28, 28));
    }

    private void Refresh_Click(object sender, RoutedEventArgs e)
    {
        _ = RefreshDevicesAsync();
    }

    private void CopyAddress_Click(object sender, RoutedEventArgs e)
    {
        if (LanAddressText.Text.StartsWith("http://", StringComparison.Ordinal))
        {
            Clipboard.SetText(LanAddressText.Text);
            StatusText.Text = "Companion server address copied to clipboard.";
        }
        else
        {
            StatusText.Text = "Connect this PC to a private Wi-Fi or Ethernet network first.";
        }
    }

    private async void Window_Closed(object? sender, EventArgs e)
    {
        _refreshTimer.Stop();
        if (_apiHost is not null)
        {
            await _apiHost.DisposeAsync();
            _apiHost = null;
        }
    }
}

public sealed class DeviceRow
{
    public DeviceRow(DeviceRecord device)
    {
        Name = string.IsNullOrWhiteSpace(device.Name) ? "Android device" : device.Name;
        Model = string.IsNullOrWhiteSpace(device.Model) ? "Unknown model" : device.Model;
        Status = DateTimeOffset.UtcNow - device.LastSeenAt <= TimeSpan.FromSeconds(90) ? "Online" : "Offline";
        Battery = device.BatteryPercent.HasValue ? device.BatteryPercent.Value + "%" : "—";
        Android = string.IsNullOrWhiteSpace(device.AndroidVersion) ? "—" : device.AndroidVersion;
        LastSeen = device.LastSeenAt.ToLocalTime().ToString("dd MMM yyyy, HH:mm:ss");
    }

    public string Name { get; }
    public string Model { get; }
    public string Status { get; }
    public string Battery { get; }
    public string Android { get; }
    public string LastSeen { get; }
}