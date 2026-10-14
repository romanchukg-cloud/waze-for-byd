package local.waze.diagnostics;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
/** Test-only SAF destination, never included in Waze. */
public final class DiagnosticsExportProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(),"export.json"),
            ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_TRUNCATE);
    }
    public String getType(Uri u){return "application/json";}
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){return 0;}
    public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}
