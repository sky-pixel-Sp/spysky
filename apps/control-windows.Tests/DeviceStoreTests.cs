using SkySpy.Control;
using Xunit;

namespace SkySpy.Control.Tests;

public sealed class DeviceStoreTests
{
    [Fact]
    public async Task RegistrationStoresOnlyTokenHashAndCreatesOneDevice()
    {
        string directory = NewTempDirectory();
        try
        {
            string file = Path.Combine(directory, "devices.json");
            var store = new DeviceStore(file);
            await store.InitializeAsync();

            Guid installId = Guid.NewGuid();
            RegistrationResult registration = await store.RegisterAsync(
                installId,
                new DeviceMetadata
                {
                    Manufacturer = "Test",
                    Model = "Owner test phone",
                    AndroidVersion = "Android test",
                    BatteryPercent = 82,
                    Charging = false,
                    AppVersion = "0.2.2"
                },
                null);

            IReadOnlyList<DeviceRecord> records = await store.GetAllAsync();
            Assert.Single(records);
            Assert.Equal("Owner test phone", records[0].Model);
            Assert.DoesNotContain(registration.DeviceToken, await File.ReadAllTextAsync(file));
            Assert.NotEmpty(records[0].TokenHash);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task ReEnrollmentRotatesTheDeviceCredential()
    {
        string directory = NewTempDirectory();
        try
        {
            var store = new DeviceStore(Path.Combine(directory, "devices.json"));
            await store.InitializeAsync();
            Guid installId = Guid.NewGuid();
            var metadata = new DeviceMetadata { Model = "Test phone" };

            RegistrationResult first = await store.RegisterAsync(installId, metadata, null);
            RegistrationResult second = await store.RegisterAsync(installId, metadata, null);

            Assert.Equal(first.DeviceId, second.DeviceId);
            Assert.NotEqual(first.DeviceToken, second.DeviceToken);
            Assert.Null(await store.HeartbeatAsync(first.DeviceToken, null));
            Assert.NotNull(await store.HeartbeatAsync(second.DeviceToken, null));
            Assert.Single(await store.GetAllAsync());
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task UnpairRemovesOnlyTheMatchingDevice()
    {
        string directory = NewTempDirectory();
        try
        {
            var store = new DeviceStore(Path.Combine(directory, "devices.json"));
            await store.InitializeAsync();
            RegistrationResult registration = await store.RegisterAsync(
                Guid.NewGuid(),
                new DeviceMetadata { Model = "Unpair test phone" },
                null);

            Assert.False(await store.UnpairAsync("not-a-device-token"));
            Assert.True(await store.UnpairAsync(registration.DeviceToken));
            Assert.Empty(await store.GetAllAsync());
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private static string NewTempDirectory()
    {
        string path = Path.Combine(Path.GetTempPath(), "skyspy-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(path);
        return path;
    }
}