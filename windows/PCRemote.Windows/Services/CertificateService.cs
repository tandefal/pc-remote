using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using PCRemote.Windows.Models;

namespace PCRemote.Windows.Services;

public static class CertificateService
{
    public static async Task<X509Certificate2> GetOrCreateAsync(AppSettings settings, SecureStore store)
    {
        if (settings.CertificatePfx is not null && settings.CertificatePassword is not null)
            return new X509Certificate2(Convert.FromBase64String(settings.CertificatePfx), settings.CertificatePassword, X509KeyStorageFlags.UserKeySet);

        using var rsa = RSA.Create(3072);
        var request = new CertificateRequest($"CN=PC Remote {settings.ComputerId}", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));
        var san = new SubjectAlternativeNameBuilder(); san.AddDnsName("pc-remote.local"); san.AddIpAddress(IPAddress.Loopback); request.CertificateExtensions.Add(san.Build());
        using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        var password = SecureStore.RandomSecret(); var pfx = generated.Export(X509ContentType.Pkcs12, password);
        settings.CertificatePfx = Convert.ToBase64String(pfx); settings.CertificatePassword = password; await store.SaveAsync(settings);
        return new X509Certificate2(pfx, password, X509KeyStorageFlags.UserKeySet);
    }

    public static string Fingerprint(X509Certificate2 certificate) => Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant();
}
