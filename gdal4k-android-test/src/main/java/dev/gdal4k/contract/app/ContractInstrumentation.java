package dev.gdal4k.contract.app;
import android.app.Instrumentation;
import android.os.Bundle;
import dev.gdal4k.contract.AndroidContract;
public class ContractInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AndroidContract.run(getTargetContext());
            result.putString("result", "GDAL4K_LIBRARY_CONTRACT_OK");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
}
