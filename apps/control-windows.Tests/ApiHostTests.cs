using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Threading.Tasks;
using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Net.Http.Headers;
using System.Text.Json;
using SkySpy.Control;
using Xunit;

namespace SkySpy.Control.Tests;

public sealed class ApiHostTests
{
    [Fact]
    public async Task EmbeddedApiIsNativeOnlyAndRequiresOwnerConsent()
    {
        string directory = Path.Combine(Path.GetTempPath(), "skyspy-api-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);

        int port;
        using (var listener = new TcpListener(IPAddress.Loopback, 0))
        {
            listener.Start();
            port = ((IPEndPoint)listener.LocalEndpoint).Port;
        }

        try
        {
            await using var host = await SkySpyApiHost.StartAsync(
                Path.Combine(directory, "devices.json"),
                port);

            using var client = new HttpClient
            {
                BaseAddress = new Uri("http://127.0.0.1:" + port + "/")
            };

            using (var root = await client.GetAsync("/"))
            {
                Assert.Equal(HttpStatusCode.NotFound, root.StatusCode);
            }

            using (var browserRequest = new HttpRequestMessage(HttpMethod.Get, "health"))
            {
                browserRequest.Headers.TryAddWithoutValidation("Origin", "https://example.invalid");
                using var browserResponse = await client.SendAsync(browserRequest);
                Assert.Equal(HttpStatusCode.Forbidden, browserResponse.StatusCode);
            }

            Guid installId = Guid.NewGuid();
            var diagnostics = new
            {
                manufacturer = "Test",
                model = "Owner test phone",
                androidVersion = "Android test",
                securityPatch = "2026-10-01",
                batteryPercent = 83,
                charging = false,
                appVersion = "0.2.2"
            };

            using (var denied = await client.PostAsJsonAsync("api/device/register", new
            {
                installId = installId.ToString(),
                consent = false,
                device = diagnostics
            }))
            {
                Assert.Equal(HttpStatusCode.Forbidden, denied.StatusCode);
            }

            using var registered = await client.PostAsJsonAsync("api/device/register", new
            {
                installId = installId.ToString(),
                consent = true,
                device = diagnostics
            });
            Assert.Equal(HttpStatusCode.Created, registered.StatusCode);

            using JsonDocument registrationBody = JsonDocument.Parse(
                await registered.Content.ReadAsStringAsync());
            string token = registrationBody.RootElement.GetProperty("deviceToken").GetString()!;
            Assert.False(string.IsNullOrWhiteSpace(token));

            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var heartbeat = await client.PostAsJsonAsync("api/device/heartbeat", new
            {
                device = new { batteryPercent = 79, charging = true }
            });
            Assert.Equal(HttpStatusCode.OK, heartbeat.StatusCode);

            using var inventory = await client.GetAsync("api/devices");
            Assert.Equal(HttpStatusCode.NotFound, inventory.StatusCode);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}