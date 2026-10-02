//#ifdef JAVA_TLS
package pubtran;

import java.io.IOException;
import java.io.OutputStream;
import org.bouncycastle.crypto.tls.AlertDescription;
import org.bouncycastle.crypto.tls.NameType;
import org.bouncycastle.crypto.tls.TlsFatalAlert;
import org.bouncycastle.crypto.tls.TlsUtils;

/**
 * SNI host name for the Java TLS path. Same as BouncyCastle's ServerName, except the
 * name is encoded without getBytes("ASCII") - some phone JVMs don't know that charset
 * name and the ClientHello would fail. Taken from discord-j2me's ModernConnector
 * (LegacyServerName).
 */
public class SniServerName extends org.bouncycastle.crypto.tls.ServerName {
    public SniServerName(String host) {
        super(NameType.host_name, host);
    }

    public void encode(OutputStream output) throws IOException {
        TlsUtils.writeUint8(nameType, output);
        if (nameType != NameType.host_name) {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }
        String host = (String) name;
        byte[] ascii = new byte[host.length()];
        for (int i = 0; i < ascii.length; i++) {
            ascii[i] = (byte) host.charAt(i);
        }
        if (ascii.length < 1) {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }
        TlsUtils.writeOpaque16(ascii, output);
    }
}
//#endif
