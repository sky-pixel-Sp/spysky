using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace SkySpy.Control;

public sealed class SkySpyApiHost : IAsyncDisposable
{
    private readonly WebApplication _app;

    public DeviceStore Store { get; }

    private SkySpyApiHost(WebApplication app, DeviceStore store)
    {
        _app = app;
        Store = store;
    }

    public static async Task<SkySpyApiHost> StartAsync()
    {
        string dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SkySpy",
            "data");
        var store = new DeviceStore(Path.Combine(dataDirectory, "devices.json"));
        await store.InitializeAsync();

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = Array.Empty<string>(),
            ContentRootPath = AppContext.BaseDirectory,
            ApplicationName = typeof(SkySpyApiHost).Assembly.FullName
        });
        builder.Logging.ClearProviders();
        IPAddress[] privateAddresses = GetPrivateLanAddresses();
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.Limits.MaxRequestBodySize = 16 * 1024;
            options.ListenLocalhost(8787);
            foreach (IPAddress address in privateAddresses)
            {
                options.Listen(address, 8787);
            }
        });
        builder.Services.AddSingleton(store);

        var app = builder.Build();

        app.Use(async (context, next) =>
        {
            if (context.Request.Headers.ContainsKey("Origin"))
            {
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                await context.Response.WriteAsJsonAsync(new
                {
                    error = "Browser access is disabled. Use the native SkySpy Control application."
                });
                return;
            }

            await next();
        });

        app.MapGet("/health", () => Results.Ok(new
        {
            ok = true,
            service = "skyspy-api",
            version = "0.3.0"
        }));

        app.MapPost("/api/device/register", async (HttpContext context, RegistrationRequest? request, DeviceStore deviceStore) =>
        {
            if (!IsPrivateLanClient(context))
            {
                return Results.Forbid();
            }

            if (request is null)
            {
                return Results.BadRequest(new { error = "A registration body is required." });
            }

            if (request.Consent != true)
            {
                return Results.Json(new
                {
                    error = "The device owner must approve sharing the listed diagnostics."
                }, statusCode: StatusCodes.Status403Forbidden);
            }

            if (!Guid.TryParse(request.InstallId, out Guid installId))
            {
                return Results.BadRequest(new { error = "Companion installation identity is invalid." });
            }

            if (request.Device is null || string.IsNullOrWhiteSpace(request.Device.Model))
            {
                return Results.BadRequest(new { error = "Device model is required." });
            }

            RegistrationResult registered = await deviceStore.RegisterAsync(
                installId,
                request.Device,
                request.DeviceName);

            return Results.Json(new
            {
                deviceId = registered.DeviceId,
                deviceToken = registered.DeviceToken,
                registeredAt = registered.RegisteredAt,
                message = "Device registered after owner approval."
            }, statusCode: StatusCodes.Status201Created);
        });

        app.MapPost("/api/device/heartbeat", async (HttpContext context, HeartbeatRequest? request, DeviceStore deviceStore) =>
        {
            if (!IsPrivateLanClient(context))
            {
                return Results.Forbid();
            }

            string token = BearerToken(context);
            if (string.IsNullOrWhiteSpace(token))
            {
                return Results.Unauthorized();
            }

            DeviceRecord? updated = await deviceStore.HeartbeatAsync(token, request?.Device);
            if (updated is null)
            {
                return Results.Json(new { error = "Device session is invalid. Connect this Companion again." },
                    statusCode: StatusCodes.Status401Unauthorized);
            }

            return Results.Ok(new
            {
                ok = true,
                deviceId = updated.Id,
                lastSeenAt = updated.LastSeenAt
            });
        });

        app.MapPost("/api/device/unpair", async (HttpContext context, DeviceStore deviceStore) =>
        {
            if (!IsPrivateLanClient(context))
            {
                return Results.Forbid();
            }

            string token = BearerToken(context);
            if (string.IsNullOrWhiteSpace(token) || !await deviceStore.UnpairAsync(token))
            {
                return Results.Json(new { error = "Device session is invalid." },
                    statusCode: StatusCodes.Status401Unauthorized);
            }

            return Results.Ok(new
            {
                ok = true,
                message = "Device removed from Control inventory."
            });
        });

        await app.StartAsync();
        return new SkySpyApiHost(app, store);
    }

    private static IPAddress[] GetPrivateLanAddresses() =>
        NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(item => item.Address)
            .Where(IsPrivateIPv4)
            .Distinct()
            .ToArray();

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

    private static bool IsPrivateLanClient(HttpContext context)
    {
        IPAddress? address = context.Connection.RemoteIpAddress;
        if (address is null)
        {
            return false;
        }

        if (address.IsIPv4MappedToIPv6)
        {
            address = address.MapToIPv4();
        }

        return IPAddress.IsLoopback(address) || IsPrivateIPv4(address);
    }

    private static string BearerToken(HttpContext context)
    {
        string header = context.Request.Headers.Authorization.ToString();
        const string prefix = "Bearer ";
        return header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            ? header[prefix.Length..].Trim()
            : "";
    }

    public async ValueTask DisposeAsync()
    {
        await _app.StopAsync(TimeSpan.FromSeconds(3));
        await _app.DisposeAsync();
    }
}

public sealed class DeviceStore
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _filePath;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private List<DeviceRecord> _devices = new();

    public DeviceStore(string filePath)
    {
        _filePath = filePath;
    }

    public async Task InitializeAsync()
    {
        await _gate.WaitAsync();
        try
        {
            string? directory = Path.GetDirectoryName(_filePath);
            if (!string.IsNullOrWhiteSpace(directory))
            {
                Directory.CreateDirectory(directory);
            }

            if (File.Exists(_filePath))
            {
                string json = await File.ReadAllTextAsync(_filePath);
                _devices = JsonSerializer.Deserialize<List<DeviceRecord>>(json, JsonOptions) ?? new List<DeviceRecord>();
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<IReadOnlyList<DeviceRecord>> GetAllAsync()
    {
        await _gate.WaitAsync();
        try
        {
            return _devices.Select(device => device with { }).ToArray();
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<RegistrationResult> RegisterAsync(Guid installId, DeviceMetadata metadata, string? deviceName)
    {
        await _gate.WaitAsync();
        try
        {
            string installHash = Hash(installId.ToString("D"));
            int index = _devices.FindIndex(device => FixedEquals(device.InstallIdHash, installHash));
            string token = CreateToken();
            DateTimeOffset now = DateTimeOffset.UtcNow;
            DeviceMetadata cleaned = Clean(metadata);

            if (index >= 0)
            {
                DeviceRecord previous = _devices[index];
                DeviceRecord updated = previous with
                {
                    Name = DisplayName(deviceName, cleaned.Model),
                    Manufacturer = cleaned.Manufacturer,
                    Model = cleaned.Model,
                    AndroidVersion = cleaned.AndroidVersion,
                    SecurityPatch = cleaned.SecurityPatch,
                    BatteryPercent = cleaned.BatteryPercent,
                    Charging = cleaned.Charging,
                    AppVersion = cleaned.AppVersion,
                    TokenHash = Hash(token),
                    LastSeenAt = now
                };
                _devices[index] = updated;
                await SaveLockedAsync();
                return new RegistrationResult(updated.Id, token, updated.CreatedAt);
            }

            var created = new DeviceRecord
            {
                Id = Guid.NewGuid().ToString("D"),
                InstallIdHash = installHash,
                TokenHash = Hash(token),
                Name = DisplayName(deviceName, cleaned.Model),
                Manufacturer = cleaned.Manufacturer,
                Model = cleaned.Model,
                AndroidVersion = cleaned.AndroidVersion,
                SecurityPatch = cleaned.SecurityPatch,
                BatteryPercent = cleaned.BatteryPercent,
                Charging = cleaned.Charging,
                AppVersion = cleaned.AppVersion,
                CreatedAt = now,
                LastSeenAt = now
            };
            _devices.Add(created);
            await SaveLockedAsync();
            return new RegistrationResult(created.Id, token, now);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<DeviceRecord?> HeartbeatAsync(string token, DeviceMetadata? metadata)
    {
        await _gate.WaitAsync();
        try
        {
            string tokenHash = Hash(token);
            int index = _devices.FindIndex(device => FixedEquals(device.TokenHash, tokenHash));
            if (index < 0)
            {
                return null;
            }

            DeviceRecord previous = _devices[index];
            DeviceMetadata? cleaned = metadata is null ? null : Clean(metadata);
            DeviceRecord updated = previous with
            {
                Manufacturer = NonEmpty(cleaned?.Manufacturer, previous.Manufacturer),
                Model = NonEmpty(cleaned?.Model, previous.Model),
                AndroidVersion = NonEmpty(cleaned?.AndroidVersion, previous.AndroidVersion),
                SecurityPatch = NonEmpty(cleaned?.SecurityPatch, previous.SecurityPatch),
                BatteryPercent = cleaned?.BatteryPercent ?? previous.BatteryPercent,
                Charging = cleaned?.Charging ?? previous.Charging,
                AppVersion = NonEmpty(cleaned?.AppVersion, previous.AppVersion),
                LastSeenAt = DateTimeOffset.UtcNow
            };
            _devices[index] = updated;
            await SaveLockedAsync();
            return updated;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<bool> UnpairAsync(string token)
    {
        await _gate.WaitAsync();
        try
        {
            string tokenHash = Hash(token);
            int index = _devices.FindIndex(device => FixedEquals(device.TokenHash, tokenHash));
            if (index < 0)
            {
                return false;
            }

            _devices.RemoveAt(index);
            await SaveLockedAsync();
            return true;
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task SaveLockedAsync()
    {
        string? directory = Path.GetDirectoryName(_filePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporary = _filePath + ".tmp";
        string json = JsonSerializer.Serialize(_devices, JsonOptions);
        await File.WriteAllTextAsync(temporary, json);
        File.Move(temporary, _filePath, overwrite: true);
    }

    private static DeviceMetadata Clean(DeviceMetadata input) => new()
    {
        Manufacturer = CleanText(input.Manufacturer, 50),
        Model = CleanText(input.Model, 80),
        AndroidVersion = CleanText(input.AndroidVersion, 50),
        SecurityPatch = CleanText(input.SecurityPatch, 20),
        BatteryPercent = input.BatteryPercent.HasValue
            ? Math.Clamp(input.BatteryPercent.Value, 0, 100)
            : null,
        Charging = input.Charging,
        AppVersion = CleanText(input.AppVersion, 30)
    };

    private static string CleanText(string? value, int maxLength)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return "";
        }

        var clean = new string(value.Trim().Where(character => !char.IsControl(character)).ToArray());
        return clean.Length <= maxLength ? clean : clean[..maxLength];
    }

    private static string NonEmpty(string? candidate, string fallback) =>
        string.IsNullOrWhiteSpace(candidate) ? fallback : candidate;

    private static string DisplayName(string? preferred, string model)
    {
        string cleaned = CleanText(preferred, 60);
        return cleaned.Length > 0 ? cleaned : model;
    }

    private static string Hash(string value) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();

    private static string CreateToken() =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

    private static bool FixedEquals(string left, string right)
    {
        byte[] leftBytes = Encoding.UTF8.GetBytes(left);
        byte[] rightBytes = Encoding.UTF8.GetBytes(right);
        return leftBytes.Length == rightBytes.Length
            && CryptographicOperations.FixedTimeEquals(leftBytes, rightBytes);
    }
}

public sealed class RegistrationRequest
{
    public string? InstallId { get; set; }
    public bool Consent { get; set; }
    public string? DeviceName { get; set; }
    public DeviceMetadata? Device { get; set; }
}

public sealed class HeartbeatRequest
{
    public DeviceMetadata? Device { get; set; }
}

public sealed class DeviceMetadata
{
    public string? Manufacturer { get; set; }
    public string? Model { get; set; }
    public string? AndroidVersion { get; set; }
    public string? SecurityPatch { get; set; }
    public int? BatteryPercent { get; set; }
    public bool? Charging { get; set; }
    public string? AppVersion { get; set; }
}

public sealed record DeviceRecord
{
    public string Id { get; init; } = "";
    public string InstallIdHash { get; init; } = "";
    public string TokenHash { get; init; } = "";
    public string Name { get; init; } = "";
    public string Manufacturer { get; init; } = "";
    public string Model { get; init; } = "";
    public string AndroidVersion { get; init; } = "";
    public string SecurityPatch { get; init; } = "";
    public int? BatteryPercent { get; init; }
    public bool? Charging { get; init; }
    public string AppVersion { get; init; } = "";
    public DateTimeOffset CreatedAt { get; init; }
    public DateTimeOffset LastSeenAt { get; init; }
}

public sealed record RegistrationResult(string DeviceId, string DeviceToken, DateTimeOffset RegisteredAt);