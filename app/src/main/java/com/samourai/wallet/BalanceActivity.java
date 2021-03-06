package com.samourai.wallet;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.Html;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AnticipateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import org.apache.commons.lang3.tuple.Pair;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.crypto.MnemonicException;

import com.dm.zbar.android.scanner.ZBarConstants;
import com.dm.zbar.android.scanner.ZBarScannerActivity;
import com.samourai.wallet.JSONRPC.JSONRPC;
import com.samourai.wallet.JSONRPC.PoW;
import com.samourai.wallet.JSONRPC.TrustedNodeUtil;
import com.samourai.wallet.access.AccessFactory;
import com.samourai.wallet.api.APIFactory;
import com.samourai.wallet.api.Tx;
import com.samourai.wallet.bip47.BIP47Meta;
import com.samourai.wallet.bip47.BIP47Util;
import com.samourai.wallet.bip47.rpc.*;
import com.samourai.wallet.crypto.AESUtil;
import com.samourai.wallet.crypto.DecryptionException;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.hd.HD_Wallet;
import com.samourai.wallet.hd.HD_WalletFactory;
import com.samourai.wallet.hf.HardForkUtil;
import com.samourai.wallet.hf.ReplayProtectionActivity;
import com.samourai.wallet.hf.ReplayProtectionWarningActivity;
import com.samourai.wallet.payload.PayloadUtil;
import com.samourai.wallet.send.FeeUtil;
import com.samourai.wallet.send.MyTransactionInput;
import com.samourai.wallet.send.MyTransactionOutPoint;
import com.samourai.wallet.send.RBFSpend;
import com.samourai.wallet.send.RBFUtil;
import com.samourai.wallet.send.SendFactory;
import com.samourai.wallet.send.SuggestedFee;
import com.samourai.wallet.send.SweepUtil;
import com.samourai.wallet.send.UTXO;
import com.samourai.wallet.send.PushTx;
import com.samourai.wallet.service.WebSocketService;
import com.samourai.wallet.util.AddressFactory;
import com.samourai.wallet.util.AppUtil;
import com.samourai.wallet.util.BlockExplorerUtil;
import com.samourai.wallet.util.CharSequenceX;
import com.samourai.wallet.util.DateUtil;
import com.samourai.wallet.util.ExchangeRateFactory;
import com.samourai.wallet.util.MonetaryUtil;
import com.samourai.wallet.util.PrefsUtil;
import com.samourai.wallet.util.PrivKeyReader;
import com.samourai.wallet.util.TimeOutUtil;
import com.samourai.wallet.util.TorUtil;
import com.samourai.wallet.util.TypefaceUtil;

import org.bitcoinj.core.Coin;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Hex;
import org.spongycastle.util.encoders.DecoderException;

import net.i2p.android.ext.floatingactionbutton.FloatingActionButton;
import net.i2p.android.ext.floatingactionbutton.FloatingActionsMenu;
import net.sourceforge.zbar.Symbol;

import java.io.IOException;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import info.guardianproject.netcipher.proxy.OrbotHelper;

public class BalanceActivity extends Activity {

    private final static int SCAN_COLD_STORAGE = 2011;
    private final static int SCAN_QR = 2012;

    private LinearLayout layoutAlert = null;

    private LinearLayout tvBalanceBar = null;
    private TextView tvBalanceAmount = null;
    private TextView tvBalanceUnits = null;

    private ListView txList = null;
    private List<Tx> txs = null;
    private HashMap<String, Boolean> txStates = null;
    private TransactionAdapter txAdapter = null;
    private SwipeRefreshLayout swipeRefreshLayout = null;

    private FloatingActionsMenu ibQuickSend = null;
    private FloatingActionButton actionReceive = null;
    private FloatingActionButton actionSend = null;
    private FloatingActionButton actionBIP47 = null;

    private boolean isBTC = true;

    private RefreshTask refreshTask = null;
    private PoWTask powTask = null;
    private RBFTask rbfTask = null;
    private CPFPTask cpfpTask = null;

    public static final String ACTION_INTENT = "com.samourai.wallet.BalanceFragment.REFRESH";
    protected BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {

            if(ACTION_INTENT.equals(intent.getAction())) {

                final boolean notifTx = intent.getBooleanExtra("notifTx", false);
                final boolean fetch = intent.getBooleanExtra("fetch", false);

                final String rbfHash;
                final String blkHash;
                if(intent.hasExtra("rbf"))    {
                    rbfHash = intent.getStringExtra("rbf");
                }
                else    {
                    rbfHash = null;
                }
                if(intent.hasExtra("hash"))    {
                    blkHash = intent.getStringExtra("hash");
                }
                else    {
                    blkHash = null;
                }

                BalanceActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvBalanceAmount.setText("");
                        tvBalanceUnits.setText("");
                        refreshTx(notifTx, fetch, false, false);

                        if(BalanceActivity.this != null)    {

                            try {
                                PayloadUtil.getInstance(BalanceActivity.this).saveWalletToJSON(new CharSequenceX(AccessFactory.getInstance(BalanceActivity.this).getGUID() + AccessFactory.getInstance(BalanceActivity.this).getPIN()));
                            }
                            catch(MnemonicException.MnemonicLengthException mle) {
                                ;
                            }
                            catch(JSONException je) {
                                ;
                            }
                            catch(IOException ioe) {
                                ;
                            }
                            catch(DecryptionException de) {
                                ;
                            }

                            if(rbfHash != null)    {
                                new AlertDialog.Builder(BalanceActivity.this)
                                        .setTitle(R.string.app_name)
                                        .setMessage(rbfHash + "\n\n" + getString(R.string.rbf_incoming))
                                        .setCancelable(true)
                                        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface dialog, int whichButton) {

                                                doExplorerView(rbfHash);

                                            }
                                        })
                                        .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                ;
                                            }
                                        }).show();

                            }

                        }

                    }
                });

                if(BalanceActivity.this != null && blkHash != null && PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.USE_TRUSTED_NODE, false) == true && TrustedNodeUtil.getInstance().isSet())    {

                    BalanceActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if(powTask == null || powTask.getStatus().equals(AsyncTask.Status.FINISHED))    {
                                powTask = new PoWTask();
                                powTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, blkHash);
                            }
                        }

                    });

                }

            }

        }
    };

    protected BroadcastReceiver torStatusReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {

            Log.i("BalanceActivity", "torStatusReceiver onReceive()");

            if (OrbotHelper.ACTION_STATUS.equals(intent.getAction())) {

                boolean enabled = (intent.getStringExtra(OrbotHelper.EXTRA_STATUS).equals(OrbotHelper.STATUS_ON));
                Log.i("BalanceActivity", "status:" + enabled);

                TorUtil.getInstance(BalanceActivity.this).setStatusFromBroadcast(enabled);

            }
        }
    };

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_balance);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        LayoutInflater inflator = BalanceActivity.this.getLayoutInflater();
        tvBalanceBar = (LinearLayout)inflator.inflate(R.layout.balance_layout, null);
        tvBalanceBar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if(isBTC)    {
                    isBTC = false;
                }
                else    {
                    isBTC = true;
                }
                displayBalance();
                txAdapter.notifyDataSetChanged();
                return false;
            }
        });
        tvBalanceAmount = (TextView)tvBalanceBar.findViewById(R.id.BalanceAmount);
        tvBalanceUnits = (TextView)tvBalanceBar.findViewById(R.id.BalanceUnits);

        ibQuickSend = (FloatingActionsMenu)findViewById(R.id.wallet_menu);
        actionSend = (FloatingActionButton)findViewById(R.id.send);
        actionSend.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {

                Intent intent = new Intent(BalanceActivity.this, SendActivity.class);
                intent.putExtra("via_menu", true);
                startActivity(intent);

            }
        });

        actionReceive = (FloatingActionButton)findViewById(R.id.receive);
        actionReceive.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {

                try {
                    HD_Wallet hdw = HD_WalletFactory.getInstance(BalanceActivity.this).get();

                    if(hdw != null)    {
                        if(SamouraiWallet.getInstance().getCurrentSelectedAccount() == 2 ||
                                (SamouraiWallet.getInstance().getCurrentSelectedAccount() == 0 && SamouraiWallet.getInstance().getShowTotalBalance())
                                )    {

                            new AlertDialog.Builder(BalanceActivity.this)
                                    .setTitle(R.string.app_name)
                                    .setMessage(R.string.receive2Samourai)
                                    .setCancelable(false)
                                    .setPositiveButton(R.string.generate_receive_yes, new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int whichButton) {
                                            Intent intent = new Intent(BalanceActivity.this, ReceiveActivity.class);
                                            startActivity(intent);
                                        }
                                    })
                                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dialog, int whichButton) {
                                            ;
                                        }
                                    }).show();

                        }
                        else    {
                            Intent intent = new Intent(BalanceActivity.this, ReceiveActivity.class);
                            startActivity(intent);
                        }
                    }

                }
                catch(IOException | MnemonicException.MnemonicLengthException e) {
                    ;
                }

            }
        });

        actionBIP47 = (FloatingActionButton)findViewById(R.id.bip47);
        actionBIP47.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                Intent intent = new Intent(BalanceActivity.this, com.samourai.wallet.bip47.BIP47Activity.class);
                startActivity(intent);
            }
        });

        txs = new ArrayList<Tx>();
        txStates = new HashMap<String, Boolean>();
        txList = (ListView)findViewById(R.id.txList);
        txAdapter = new TransactionAdapter();
        txList.setAdapter(txAdapter);
        txList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, final View view, int position, long id) {

                if(position == 0) {
                    return;
                }

                long viewId = view.getId();
                View v = (View)view.getParent();
                final Tx tx = txs.get(position - 1);
                ImageView ivTxStatus = (ImageView)v.findViewById(R.id.TransactionStatus);
                TextView tvConfirmationCount = (TextView)v.findViewById(R.id.ConfirmationCount);

                if(viewId == R.id.ConfirmationCount || viewId == R.id.TransactionStatus) {

                    if(txStates.containsKey(tx.getHash()) && txStates.get(tx.getHash()) == true) {
                        txStates.put(tx.getHash(), false);
                        displayTxStatus(false, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                    }
                    else {
                        txStates.put(tx.getHash(), true);
                        displayTxStatus(true, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                    }

                }
                else {

                    String message = getString(R.string.options_unconfirmed_tx);

                    // RBF
                    if(tx.getConfirmations() < 1 && tx.getAmount() < 0.0 && RBFUtil.getInstance().contains(tx.getHash()))    {
                        AlertDialog.Builder builder = new AlertDialog.Builder(BalanceActivity.this);
                        builder.setTitle(R.string.app_name);
                        builder.setMessage(message);
                        builder.setCancelable(true);
                        builder.setPositiveButton(R.string.options_bump_fee, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {

                                if(rbfTask == null || rbfTask.getStatus().equals(AsyncTask.Status.FINISHED))    {
                                    rbfTask = new RBFTask();
                                    rbfTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, tx.getHash());
                                }

                            }
                        });
                        builder.setNegativeButton(R.string.options_block_explorer, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {
                                doExplorerView(tx.getHash());
                            }
                        });

                        AlertDialog alert = builder.create();
                        alert.show();
                    }
                    // CPFP receive
                    else if(tx.getConfirmations() < 1 && tx.getAmount() >= 0.0)   {
                        AlertDialog.Builder builder = new AlertDialog.Builder(BalanceActivity.this);
                        builder.setTitle(R.string.app_name);
                        builder.setMessage(message);
                        builder.setCancelable(true);
                        builder.setPositiveButton(R.string.options_bump_fee, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {

                                if(cpfpTask == null || cpfpTask.getStatus().equals(AsyncTask.Status.FINISHED))    {
                                    cpfpTask = new CPFPTask();
                                    cpfpTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, tx.getHash());
                                }

                            }
                        });
                        builder.setNegativeButton(R.string.options_block_explorer, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {
                                doExplorerView(tx.getHash());
                            }
                        });

                        AlertDialog alert = builder.create();
                        alert.show();
                    }
                    // CPFP spend
                    else if(tx.getConfirmations() < 1 && tx.getAmount() < 0.0)   {
                        AlertDialog.Builder builder = new AlertDialog.Builder(BalanceActivity.this);
                        builder.setTitle(R.string.app_name);
                        builder.setMessage(message);
                        builder.setCancelable(true);
                        builder.setPositiveButton(R.string.options_bump_fee, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {

                                if(cpfpTask == null || cpfpTask.getStatus().equals(AsyncTask.Status.FINISHED))    {
                                    cpfpTask = new CPFPTask();
                                    cpfpTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, tx.getHash());
                                }

                            }
                        });
                        builder.setNegativeButton(R.string.options_block_explorer, new DialogInterface.OnClickListener() {
                            public void onClick(final DialogInterface dialog, int whichButton) {
                                doExplorerView(tx.getHash());
                            }
                        });

                        AlertDialog alert = builder.create();
                        alert.show();
                    }
                    else    {
                        doExplorerView(tx.getHash());
                        return;
                    }

                }

            }
        });

        swipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swiperefresh);
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {

                new Handler().post(new Runnable() {
                    @Override
                    public void run() {
                        refreshTx(false, true, true, false);
                    }
                });

            }
        });
        swipeRefreshLayout.setColorSchemeResources(android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);

        IntentFilter filter = new IntentFilter(ACTION_INTENT);
        LocalBroadcastManager.getInstance(BalanceActivity.this).registerReceiver(receiver, filter);

//        TorUtil.getInstance(BalanceActivity.this).setStatusFromBroadcast(false);
        registerReceiver(torStatusReceiver, new IntentFilter(OrbotHelper.ACTION_STATUS));

        refreshTx(false, true, false, true);

        //
        // user checks mnemonic & passphrase
        //
        if(PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.CREDS_CHECK, 0L) == 0L)    {

            AlertDialog.Builder dlg = new AlertDialog.Builder(BalanceActivity.this)
                    .setTitle(R.string.recovery_checkup)
                    .setMessage(BalanceActivity.this.getText(R.string.recovery_checkup_message))
                    .setCancelable(false)
                    .setPositiveButton(R.string.next, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {

                            dialog.dismiss();

                            try {
                                final String seed = HD_WalletFactory.getInstance(BalanceActivity.this).get().getMnemonic();
                                final String passphrase = HD_WalletFactory.getInstance(BalanceActivity.this).get().getPassphrase();

                                final String message = BalanceActivity.this.getText(R.string.mnemonic) + ":<br><br><b>" + seed + "</b><br><br>" +
                                        BalanceActivity.this.getText(R.string.passphrase) + ":<br><br><b>" + passphrase + "</b>";

                                AlertDialog.Builder dlg = new AlertDialog.Builder(BalanceActivity.this)
                                        .setTitle(R.string.recovery_checkup)
                                        .setMessage(Html.fromHtml(message))
                                        .setCancelable(false)
                                        .setPositiveButton(R.string.recovery_checkup_finish, new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                PrefsUtil.getInstance(BalanceActivity.this).setValue(PrefsUtil.CREDS_CHECK, System.currentTimeMillis() / 1000L);
                                                dialog.dismiss();
                                            }
                                        });
                                if(!isFinishing())    {
                                    dlg.show();
                                }

                            }
                            catch(IOException | MnemonicException.MnemonicLengthException e) {
                                Toast.makeText(BalanceActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                            }

                        }
                    });
            if(!isFinishing())    {
                dlg.show();
            }

        }

    }

    @Override
    public void onResume() {
        super.onResume();

//        IntentFilter filter = new IntentFilter(ACTION_INTENT);
//        LocalBroadcastManager.getInstance(BalanceActivity.this).registerReceiver(receiver, filter);

        if(TorUtil.getInstance(BalanceActivity.this).statusFromBroadcast())    {
            OrbotHelper.requestStartTor(BalanceActivity.this);
        }

        AppUtil.getInstance(BalanceActivity.this).checkTimeOut();

        if(!AppUtil.getInstance(BalanceActivity.this.getApplicationContext()).isServiceRunning(WebSocketService.class)) {
            startService(new Intent(BalanceActivity.this.getApplicationContext(), WebSocketService.class));
        }

    }

    @Override
    public void onPause() {
        super.onPause();

//        LocalBroadcastManager.getInstance(BalanceActivity.this).unregisterReceiver(receiver);

        ibQuickSend.collapse();

    }

    @Override
    public void onDestroy() {

        LocalBroadcastManager.getInstance(BalanceActivity.this).unregisterReceiver(receiver);

        unregisterReceiver(torStatusReceiver);

        if(AppUtil.getInstance(BalanceActivity.this.getApplicationContext()).isServiceRunning(WebSocketService.class)) {
            stopService(new Intent(BalanceActivity.this.getApplicationContext(), WebSocketService.class));
        }

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        if(!OrbotHelper.isOrbotInstalled(BalanceActivity.this))    {
            menu.findItem(R.id.action_tor).setVisible(false);
        }
        else if(TorUtil.getInstance(BalanceActivity.this).statusFromBroadcast())   {
            OrbotHelper.requestStartTor(BalanceActivity.this);
            menu.findItem(R.id.action_tor).setIcon(R.drawable.tor_on);
        }
        else    {
            menu.findItem(R.id.action_tor).setIcon(R.drawable.tor_off);
        }
        menu.findItem(R.id.action_refresh).setVisible(false);
        menu.findItem(R.id.action_share_receive).setVisible(false);
        menu.findItem(R.id.action_ricochet).setVisible(false);
        menu.findItem(R.id.action_empty_ricochet).setVisible(false);
        menu.findItem(R.id.action_sign).setVisible(false);
        menu.findItem(R.id.action_fees).setVisible(false);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        // noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            doSettings();
        }
        else if (id == R.id.action_sweep) {
            doSweep();
        }
        else if (id == R.id.action_utxo) {
            doUTXO();
        }
        else if (id == R.id.action_tor) {

            if(!OrbotHelper.isOrbotInstalled(BalanceActivity.this))    {
                ;
            }
            else if(TorUtil.getInstance(BalanceActivity.this).statusFromBroadcast())    {
                item.setIcon(R.drawable.tor_off);
                TorUtil.getInstance(BalanceActivity.this).setStatusFromBroadcast(false);
            }
            else    {
                OrbotHelper.requestStartTor(BalanceActivity.this);
                item.setIcon(R.drawable.tor_on);
                TorUtil.getInstance(BalanceActivity.this).setStatusFromBroadcast(true);
            }

            return true;

        }
        else if (id == R.id.action_backup) {

            if(SamouraiWallet.getInstance().hasPassphrase(BalanceActivity.this))    {
                try {
                    if(HD_WalletFactory.getInstance(BalanceActivity.this).get() != null && SamouraiWallet.getInstance().hasPassphrase(BalanceActivity.this))    {
                        doBackup();
                    }
                    else    {

                        AlertDialog.Builder builder = new AlertDialog.Builder(this);
                        builder.setMessage(R.string.passphrase_needed_for_backup).setCancelable(false);
                        AlertDialog alert = builder.create();

                        alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.ok), new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.dismiss();
                            }});

                        if(!isFinishing())    {
                            alert.show();
                        }

                    }
                }
                catch(MnemonicException.MnemonicLengthException mle) {
                    ;
                }
                catch(IOException ioe) {
                    ;
                }
            }
            else    {
                Toast.makeText(BalanceActivity.this, R.string.passphrase_required, Toast.LENGTH_SHORT).show();
            }

        }
        else if (id == R.id.action_scan_qr) {
            doScan();
        }
        else {
            ;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

        if(resultCode == Activity.RESULT_OK && requestCode == SCAN_COLD_STORAGE)	{

            if(data != null && data.getStringExtra(ZBarConstants.SCAN_RESULT) != null)	{

                final String strResult = data.getStringExtra(ZBarConstants.SCAN_RESULT);

                doPrivKey(strResult);

            }
        }
        else if(resultCode == Activity.RESULT_CANCELED && requestCode == SCAN_COLD_STORAGE)	{
            ;
        }
        else if(resultCode == Activity.RESULT_OK && requestCode == SCAN_QR)	{

            if(data != null && data.getStringExtra(ZBarConstants.SCAN_RESULT) != null)	{

                final String strResult = data.getStringExtra(ZBarConstants.SCAN_RESULT);

                Intent intent = new Intent(BalanceActivity.this, SendActivity.class);
                intent.putExtra("uri", strResult);
                startActivity(intent);

            }
        }
        else if(resultCode == Activity.RESULT_CANCELED && requestCode == SCAN_QR)	{
            ;
        }
        else {
            ;
        }

    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        if(keyCode == KeyEvent.KEYCODE_BACK) {

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.ask_you_sure_exit).setCancelable(false);
            AlertDialog alert = builder.create();

            alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.yes), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {

                    try {
                        PayloadUtil.getInstance(BalanceActivity.this).saveWalletToJSON(new CharSequenceX(AccessFactory.getInstance(BalanceActivity.this).getGUID() + AccessFactory.getInstance(BalanceActivity.this).getPIN()));
                    }
                    catch(MnemonicException.MnemonicLengthException mle) {
                        ;
                    }
                    catch(JSONException je) {
                        ;
                    }
                    catch(IOException ioe) {
                        ;
                    }
                    catch(DecryptionException de) {
                        ;
                    }

                    Intent intent = new Intent(BalanceActivity.this, ExodusActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    BalanceActivity.this.startActivity(intent);

                }});

            alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.no), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });

            if(!isFinishing())    {
                alert.show();
            }

            return true;
        }
        else	{
            ;
        }

        return false;
    }

    private void doSettings()	{
        TimeOutUtil.getInstance().updatePin();
        Intent intent = new Intent(BalanceActivity.this, SettingsActivity.class);
        startActivity(intent);
    }

    private void doUTXO()	{
        Intent intent = new Intent(BalanceActivity.this, UTXOActivity.class);
        startActivity(intent);
    }

    private void doScan() {
        Intent intent = new Intent(BalanceActivity.this, ZBarScannerActivity.class);
        intent.putExtra(ZBarConstants.SCAN_MODES, new int[]{ Symbol.QRCODE } );
        startActivityForResult(intent, SCAN_QR);
    }

    private void doSweepViaScan()	{
        Intent intent = new Intent(BalanceActivity.this, ZBarScannerActivity.class);
        intent.putExtra(ZBarConstants.SCAN_MODES, new int[]{ Symbol.QRCODE } );
        startActivityForResult(intent, SCAN_COLD_STORAGE);
    }

    private void doSweep()   {

        AlertDialog.Builder dlg = new AlertDialog.Builder(BalanceActivity.this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.action_sweep)
                .setCancelable(false)
                .setPositiveButton(R.string.enter_privkey, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        final EditText privkey = new EditText(BalanceActivity.this);
                        privkey.setInputType(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

                        AlertDialog.Builder dlg = new AlertDialog.Builder(BalanceActivity.this)
                                .setTitle(R.string.app_name)
                                .setMessage(R.string.enter_privkey)
                                .setView(privkey)
                                .setCancelable(false)
                                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {

                                        final String strPrivKey = privkey.getText().toString();

                                        if(strPrivKey != null && strPrivKey.length() > 0)    {
                                            doPrivKey(strPrivKey);
                                        }

                                    }
                                }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {

                                        dialog.dismiss();

                                    }
                                });
                        if(!isFinishing())    {
                            dlg.show();
                        }

                    }

                }).setNegativeButton(R.string.scan, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {

                        doSweepViaScan();

                    }
                });
        if(!isFinishing())    {
            dlg.show();
        }

    }

    private void doPrivKey(final String data) {

        PrivKeyReader privKeyReader = null;

        String format = null;
        try	{
            privKeyReader = new PrivKeyReader(new CharSequenceX(data), null);
            format = privKeyReader.getFormat();
        }
        catch(Exception e)	{
            Toast.makeText(BalanceActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        if(format != null)	{

            if(format.equals(PrivKeyReader.BIP38))	{

                final PrivKeyReader pvr = privKeyReader;

                final EditText password38 = new EditText(BalanceActivity.this);

                AlertDialog.Builder dlg = new AlertDialog.Builder(BalanceActivity.this)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.bip38_pw)
                        .setView(password38)
                        .setCancelable(false)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {

                                String password = password38.getText().toString();

                                ProgressDialog progress = new ProgressDialog(BalanceActivity.this);
                                progress.setCancelable(false);
                                progress.setTitle(R.string.app_name);
                                progress.setMessage(getString(R.string.decrypting_bip38));
                                progress.show();

                                boolean keyDecoded = false;

                                try {
                                    BIP38PrivateKey bip38 = new BIP38PrivateKey(MainNetParams.get(), data);
                                    final ECKey ecKey = bip38.decrypt(password);
                                    if(ecKey != null && ecKey.hasPrivKey()) {

                                        if(progress != null && progress.isShowing())    {
                                            progress.cancel();
                                        }

                                        pvr.setPassword(new CharSequenceX(password));
                                        keyDecoded = true;

                                        Toast.makeText(BalanceActivity.this, pvr.getFormat(), Toast.LENGTH_SHORT).show();
                                        Toast.makeText(BalanceActivity.this, pvr.getKey().toAddress(MainNetParams.get()).toString(), Toast.LENGTH_SHORT).show();

                                    }
                                }
                                catch(Exception e) {
                                    e.printStackTrace();
                                    Toast.makeText(BalanceActivity.this, R.string.bip38_pw_error, Toast.LENGTH_SHORT).show();
                                }

                                if(progress != null && progress.isShowing())    {
                                    progress.cancel();
                                }

                                if(keyDecoded)    {
                                    SweepUtil.getInstance(BalanceActivity.this).sweep(pvr);
                                }

                            }
                        }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {

                                Toast.makeText(BalanceActivity.this, R.string.bip38_pw_error, Toast.LENGTH_SHORT).show();

                            }
                        });
                if(!isFinishing())    {
                    dlg.show();
                }

            }
            else if(privKeyReader != null)	{
                SweepUtil.getInstance(BalanceActivity.this).sweep(privKeyReader);
            }
            else    {
                ;
            }

        }
        else    {
            Toast.makeText(BalanceActivity.this, R.string.cannot_recognize_privkey, Toast.LENGTH_SHORT).show();
        }

    }

    private void doBackup() {

        try {
            final String passphrase = HD_WalletFactory.getInstance(BalanceActivity.this).get().getPassphrase();

            final String[] export_methods = new String[2];
            export_methods[0] = getString(R.string.export_to_clipboard);
            export_methods[1] = getString(R.string.export_to_email);

            new AlertDialog.Builder(BalanceActivity.this)
                    .setTitle(R.string.options_export)
                    .setSingleChoiceItems(export_methods, 0, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {

                                    try {
                                        PayloadUtil.getInstance(BalanceActivity.this).saveWalletToJSON(new CharSequenceX(AccessFactory.getInstance(BalanceActivity.this).getGUID() + AccessFactory.getInstance(BalanceActivity.this).getPIN()));
                                    }
                                    catch (IOException ioe) {
                                        ;
                                    }
                                    catch (JSONException je) {
                                        ;
                                    }
                                    catch (DecryptionException de) {
                                        ;
                                    }
                                    catch (MnemonicException.MnemonicLengthException mle) {
                                        ;
                                    }

                                    String encrypted = null;
                                    try {
                                        encrypted = AESUtil.encrypt(PayloadUtil.getInstance(BalanceActivity.this).getPayload().toString(), new CharSequenceX(passphrase), AESUtil.DefaultPBKDF2Iterations);
                                    } catch (Exception e) {
                                        Toast.makeText(BalanceActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                                    } finally {
                                        if (encrypted == null) {
                                            Toast.makeText(BalanceActivity.this, R.string.encryption_error, Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                    }

                                    JSONObject obj = PayloadUtil.getInstance(BalanceActivity.this).putPayload(encrypted, true);

                                    if (which == 0) {
                                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                        android.content.ClipData clip = null;
                                        clip = android.content.ClipData.newPlainText("Wallet backup", obj.toString());
                                        clipboard.setPrimaryClip(clip);
                                        Toast.makeText(BalanceActivity.this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                                    } else {
                                        Intent email = new Intent(Intent.ACTION_SEND);
                                        email.putExtra(Intent.EXTRA_SUBJECT, "Samourai Wallet backup");
                                        email.putExtra(Intent.EXTRA_TEXT, obj.toString());
                                        email.setType("message/rfc822");
                                        startActivity(Intent.createChooser(email, BalanceActivity.this.getText(R.string.choose_email_client)));
                                    }

                                    dialog.dismiss();
                                }
                            }
                    ).show();

        }
        catch(IOException ioe) {
            ioe.printStackTrace();
            Toast.makeText(BalanceActivity.this, "HD wallet error", Toast.LENGTH_SHORT).show();
        }
        catch(MnemonicException.MnemonicLengthException mle) {
            mle.printStackTrace();
            Toast.makeText(BalanceActivity.this, "HD wallet error", Toast.LENGTH_SHORT).show();
        }

    }

    private class TransactionAdapter extends BaseAdapter {

        private LayoutInflater inflater = null;
        private static final int TYPE_ITEM = 0;
        private static final int TYPE_BALANCE = 1;

        TransactionAdapter() {
            inflater = (LayoutInflater)BalanceActivity.this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public int getCount() {
            if(txs == null) {
                txs = new ArrayList<Tx>();
                txStates = new HashMap<String, Boolean>();
            }
            return txs.size() + 1;
        }

        @Override
        public String getItem(int position) {
            if(txs == null) {
                txs = new ArrayList<Tx>();
                txStates = new HashMap<String, Boolean>();
            }
            if(position == 0) {
                return "";
            }
            return txs.get(position - 1).toString();
        }

        @Override
        public long getItemId(int position) {
            return position - 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_BALANCE : TYPE_ITEM;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public View getView(final int position, View convertView, final ViewGroup parent) {

            View view = null;

            int type = getItemViewType(position);
            if(convertView == null) {
                if(type == TYPE_BALANCE) {
                    view = tvBalanceBar;
                }
                else {
                    view = inflater.inflate(R.layout.tx_layout_simple, parent, false);
                }
            }
            else {
                view = convertView;
            }

            if(type == TYPE_BALANCE) {
                ;
            }
            else {
                view.findViewById(R.id.TransactionStatus).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ((ListView)parent).performItemClick(v, position, 0);
                    }
                });

                view.findViewById(R.id.ConfirmationCount).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ((ListView)parent).performItemClick(v, position, 0);
                    }
                });

                Tx tx = txs.get(position - 1);

                TextView tvTodayLabel = (TextView)view.findViewById(R.id.TodayLabel);
                String strDateGroup = DateUtil.getInstance(BalanceActivity.this).group(tx.getTS());
                if(position == 1) {
                    tvTodayLabel.setText(strDateGroup);
                    tvTodayLabel.setVisibility(View.VISIBLE);
                }
                else {
                    Tx prevTx = txs.get(position - 2);
                    String strPrevDateGroup = DateUtil.getInstance(BalanceActivity.this).group(prevTx.getTS());

                    if(strPrevDateGroup.equals(strDateGroup)) {
                        tvTodayLabel.setVisibility(View.GONE);
                    }
                    else {
                        tvTodayLabel.setText(strDateGroup);
                        tvTodayLabel.setVisibility(View.VISIBLE);
                    }
                }

                String strDetails = null;
                String strTS = DateUtil.getInstance(BalanceActivity.this).formatted(tx.getTS());
                long _amount = 0L;
                if(tx.getAmount() < 0.0) {
                    _amount = Math.abs((long)tx.getAmount());
                    strDetails = BalanceActivity.this.getString(R.string.you_sent);
                }
                else {
                    _amount = (long)tx.getAmount();
                    strDetails = BalanceActivity.this.getString(R.string.you_received);
                }
                String strAmount = null;
                String strUnits = null;
                if(isBTC)    {
                    strAmount = getBTCDisplayAmount(_amount);
                    strUnits = getBTCDisplayUnits();
                }
                else    {
                    strAmount = getFiatDisplayAmount(_amount);
                    strUnits = getFiatDisplayUnits();
                }

                TextView tvDirection = (TextView)view.findViewById(R.id.TransactionDirection);
                TextView tvDirection2 = (TextView)view.findViewById(R.id.TransactionDirection2);
                TextView tvDetails = (TextView)view.findViewById(R.id.TransactionDetails);
                ImageView ivTxStatus = (ImageView)view.findViewById(R.id.TransactionStatus);
                TextView tvConfirmationCount = (TextView)view.findViewById(R.id.ConfirmationCount);

                tvDirection.setTypeface(TypefaceUtil.getInstance(BalanceActivity.this).getAwesomeTypeface());
                if(tx.getAmount() < 0.0) {
                    tvDirection.setTextColor(Color.RED);
                    tvDirection.setText(Character.toString((char) TypefaceUtil.awesome_arrow_up));
                }
                else {
                    tvDirection.setTextColor(Color.GREEN);
                    tvDirection.setText(Character.toString((char) TypefaceUtil.awesome_arrow_down));
                }

                if(txStates.containsKey(tx.getHash()) && txStates.get(tx.getHash()) == false) {
                    txStates.put(tx.getHash(), false);
                    displayTxStatus(false, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                }
                else {
                    txStates.put(tx.getHash(), true);
                    displayTxStatus(true, tx.getConfirmations(), tvConfirmationCount, ivTxStatus);
                }

                tvDirection2.setText(strDetails + " " + strAmount + " " + strUnits);
                if(tx.getPaymentCode() != null)    {
                    String strTaggedTS = strTS + " ";
                    String strSubText = " " + BIP47Meta.getInstance().getDisplayLabel(tx.getPaymentCode()) + " ";
                    strTaggedTS += strSubText;
                    tvDetails.setText(strTaggedTS);
                } else {
                    tvDetails.setText(strTS);
                }
            }

            return view;
        }

    }

    private void refreshTx(final boolean notifTx, final boolean fetch, final boolean dragged, final boolean launch) {

        if(refreshTask == null || refreshTask.getStatus().equals(AsyncTask.Status.FINISHED))    {
            refreshTask = new RefreshTask(dragged, launch);
            refreshTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, notifTx ? "1" : "0", fetch ? "1" : "0");
        }

    }

    private void displayBalance() {
        String strFiat = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.CURRENT_FIAT, "USD");
        double btc_fx = ExchangeRateFactory.getInstance(BalanceActivity.this).getAvgPrice(strFiat);

        long balance = 0L;
        if(SamouraiWallet.getInstance().getShowTotalBalance())    {
            if(SamouraiWallet.getInstance().getCurrentSelectedAccount() == 0)    {
                balance = APIFactory.getInstance(BalanceActivity.this).getXpubBalance();
            }
            else    {
                if(APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().size() > 0)    {
                    try    {
                        if(APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().get(HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(SamouraiWallet.getInstance().getCurrentSelectedAccount() - 1).xpubstr()) != null)    {
                            balance = APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().get(HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(SamouraiWallet.getInstance().getCurrentSelectedAccount() - 1).xpubstr());
                        }
                    }
                    catch(IOException ioe)    {
                        ;
                    }
                    catch(MnemonicException.MnemonicLengthException mle)    {
                        ;
                    }
                }
            }
        }
        else    {
            if(APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().size() > 0)    {
                try    {
                    if(APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().get(HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(SamouraiWallet.getInstance().getCurrentSelectedAccount()).xpubstr()) != null)    {
                        balance = APIFactory.getInstance(BalanceActivity.this).getXpubAmounts().get(HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(SamouraiWallet.SAMOURAI_ACCOUNT).xpubstr());
                    }
                }
                catch(IOException ioe)    {
                    ;
                }
                catch(MnemonicException.MnemonicLengthException mle)    {
                    ;
                }
            }
        }
        double btc_balance = (((double)balance) / 1e8);
        double fiat_balance = btc_fx * btc_balance;

        if(isBTC) {
            tvBalanceAmount.setText(getBTCDisplayAmount(balance));
            tvBalanceUnits.setText(getBTCDisplayUnits());
        }
        else {
            tvBalanceAmount.setText(MonetaryUtil.getInstance().getFiatFormat(strFiat).format(fiat_balance));
            tvBalanceUnits.setText(strFiat);
        }

    }

    private String getBTCDisplayAmount(long value) {

        String strAmount = null;
        DecimalFormat df = new DecimalFormat("#");
        df.setMinimumIntegerDigits(1);
        df.setMinimumFractionDigits(1);
        df.setMaximumFractionDigits(8);

        int unit = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC);
        switch(unit) {
            case MonetaryUtil.MICRO_BTC:
                strAmount = df.format(((double)(value * 1000000L)) / 1e8);
                break;
            case MonetaryUtil.MILLI_BTC:
                strAmount = df.format(((double)(value * 1000L)) / 1e8);
                break;
            default:
                strAmount = Coin.valueOf(value).toPlainString();
                break;
        }

        return strAmount;
    }

    private String getBTCDisplayUnits() {

        return (String) MonetaryUtil.getInstance().getBTCUnits()[PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BTC_UNITS, MonetaryUtil.UNIT_BTC)];

    }

    private String getFiatDisplayAmount(long value) {

        String strFiat = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.CURRENT_FIAT, "USD");
        double btc_fx = ExchangeRateFactory.getInstance(BalanceActivity.this).getAvgPrice(strFiat);
        String strAmount = MonetaryUtil.getInstance().getFiatFormat(strFiat).format(btc_fx * (((double)value) / 1e8));

        return strAmount;
    }

    private String getFiatDisplayUnits() {

        return PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.CURRENT_FIAT, "USD");

    }

    private void displayTxStatus(boolean heads, long confirmations, TextView tvConfirmationCount, ImageView ivTxStatus)	{

        if(heads)	{
            if(confirmations == 0) {
                rotateTxStatus(tvConfirmationCount, true);
                ivTxStatus.setVisibility(View.VISIBLE);
                ivTxStatus.setImageResource(R.drawable.ic_query_builder_white);
                tvConfirmationCount.setVisibility(View.GONE);
            }
            else if(confirmations > 3) {
                rotateTxStatus(tvConfirmationCount, true);
                ivTxStatus.setVisibility(View.VISIBLE);
                ivTxStatus.setImageResource(R.drawable.ic_done_white);
                tvConfirmationCount.setVisibility(View.GONE);
            }
            else {
                rotateTxStatus(ivTxStatus, false);
                tvConfirmationCount.setVisibility(View.VISIBLE);
                tvConfirmationCount.setText(Long.toString(confirmations));
                ivTxStatus.setVisibility(View.GONE);
            }
        }
        else	{
            if(confirmations < 100) {
                rotateTxStatus(ivTxStatus, false);
                tvConfirmationCount.setVisibility(View.VISIBLE);
                tvConfirmationCount.setText(Long.toString(confirmations));
                ivTxStatus.setVisibility(View.GONE);
            }
            else    {
                rotateTxStatus(ivTxStatus, false);
                tvConfirmationCount.setVisibility(View.VISIBLE);
                tvConfirmationCount.setText("\u221e");
                ivTxStatus.setVisibility(View.GONE);
            }
        }

    }

    private void rotateTxStatus(View view, boolean clockwise)	{

        float degrees = 360f;
        if(!clockwise)	{
            degrees = -360f;
        }

        ObjectAnimator animation = ObjectAnimator.ofFloat(view, "rotationY", 0.0f, degrees);
        animation.setDuration(1000);
        animation.setRepeatCount(0);
        animation.setInterpolator(new AnticipateInterpolator());
        animation.start();
    }

    private void doExplorerView(String strHash)   {

        if(strHash != null) {
            int sel = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BLOCK_EXPLORER, 0);
            if(sel >= BlockExplorerUtil.getInstance().getBlockExplorerTxUrls().length)    {
                sel = 0;
            }
            CharSequence url = BlockExplorerUtil.getInstance().getBlockExplorerTxUrls()[sel];

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url + strHash));
            startActivity(browserIntent);
        }

    }

    private class RefreshTask extends AsyncTask<String, Void, String> {

        private String strProgressTitle = null;
        private String strProgressMessage = null;

        private ProgressDialog progress = null;
        private Handler handler = null;
        private boolean dragged = false;
        private boolean launch = false;

        public RefreshTask(boolean dragged, boolean launch) {
            super();
            Log.d("BalanceActivity", "RefreshTask, dragged==" + dragged);
            handler = new Handler();
            this.dragged = dragged;
            this.launch = launch;
        }

        @Override
        protected void onPreExecute() {

            Log.d("BalanceActivity", "RefreshTask.preExecute()");

            if(progress != null && progress.isShowing())    {
                progress.dismiss();
            }

            if(!dragged)    {
                strProgressTitle = BalanceActivity.this.getText(R.string.app_name).toString();
                strProgressMessage = BalanceActivity.this.getText(R.string.refresh_tx_pre).toString();

                progress = new ProgressDialog(BalanceActivity.this);
                progress.setCancelable(true);
                progress.setTitle(strProgressTitle);
                progress.setMessage(strProgressMessage);
                progress.show();
            }

        }

        @Override
        protected String doInBackground(String... params) {

            Log.d("BalanceActivity", "doInBackground()");

            final boolean notifTx = params[0].equals("1") ? true : false;
            final boolean fetch = params[1].equals("1") ? true : false;

            //
            // TBD: check on lookahead/lookbehind for all incoming payment codes
            //
            if(fetch || txs.size() == 0)    {
                Log.d("BalanceActivity", "initWallet()");
                APIFactory.getInstance(BalanceActivity.this).initWallet();
            }

            try {
                int acc = 0;
                if(SamouraiWallet.getInstance().getShowTotalBalance())    {
                    if(SamouraiWallet.getInstance().getCurrentSelectedAccount() == 0)    {
                        txs = APIFactory.getInstance(BalanceActivity.this).getAllXpubTxs();
                    }
                    else    {
                        acc = SamouraiWallet.getInstance().getCurrentSelectedAccount() - 1;
                        txs = APIFactory.getInstance(BalanceActivity.this).getXpubTxs().get(HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(acc).xpubstr());
                    }
                }
                else    {
                    txs = APIFactory.getInstance(BalanceActivity.this).getXpubTxs().get(HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(acc).xpubstr());
                }
                if(txs != null)    {
                    Collections.sort(txs, new APIFactory.TxMostRecentDateComparator());
                }

                if(AddressFactory.getInstance().getHighestTxReceiveIdx(acc) > HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(acc).getReceive().getAddrIdx()) {
                    HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(acc).getReceive().setAddrIdx(AddressFactory.getInstance().getHighestTxReceiveIdx(acc));
                }
                if(AddressFactory.getInstance().getHighestTxChangeIdx(acc) > HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(acc).getChange().getAddrIdx()) {
                    HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(acc).getChange().setAddrIdx(AddressFactory.getInstance().getHighestTxChangeIdx(acc));
                }
            }
            catch(IOException ioe) {
                ioe.printStackTrace();
            }
            catch(MnemonicException.MnemonicLengthException mle) {
                mle.printStackTrace();
            }
            finally {
                ;
            }

            if(!dragged)    {
                strProgressMessage = BalanceActivity.this.getText(R.string.refresh_tx).toString();
                publishProgress();
            }

            handler.post(new Runnable() {
                public void run() {
                    if(dragged)    {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                    tvBalanceAmount.setText("");
                    tvBalanceUnits.setText("");
                    displayBalance();
                    txAdapter.notifyDataSetChanged();
                }
            });

            PrefsUtil.getInstance(BalanceActivity.this).setValue(PrefsUtil.FIRST_RUN, false);

            if(notifTx)    {
                //
                // check for incoming payment code notification tx
                //
                try {
                    PaymentCode pcode = BIP47Util.getInstance(BalanceActivity.this).getPaymentCode();
                    APIFactory.getInstance(BalanceActivity.this).getNotifAddress(pcode.notificationAddress().getAddressString());
//                    Log.i("BalanceFragment", "payment code:" + pcode.toString());
//                    Log.i("BalanceFragment", "notification address:" + pcode.notificationAddress().getAddressString());
                }
                catch (AddressFormatException afe) {
                    afe.printStackTrace();
                    Toast.makeText(BalanceActivity.this, "HD wallet error", Toast.LENGTH_SHORT).show();
                }

                strProgressMessage = BalanceActivity.this.getText(R.string.refresh_incoming_notif_tx).toString();
                publishProgress();

                //
                // check on outgoing payment code notification tx
                //
                List<Pair<String,String>> outgoingUnconfirmed = BIP47Meta.getInstance().getOutgoingUnconfirmed();
//                Log.i("BalanceFragment", "outgoingUnconfirmed:" + outgoingUnconfirmed.size());
                for(Pair<String,String> pair : outgoingUnconfirmed)   {
//                    Log.i("BalanceFragment", "outgoing payment code:" + pair.getLeft());
//                    Log.i("BalanceFragment", "outgoing payment code tx:" + pair.getRight());
                    int confirmations = APIFactory.getInstance(BalanceActivity.this).getNotifTxConfirmations(pair.getRight());
                    if(confirmations > 0)    {
                        BIP47Meta.getInstance().setOutgoingStatus(pair.getLeft(), BIP47Meta.STATUS_SENT_CFM);
                    }
                    if(confirmations == -1)    {
                        BIP47Meta.getInstance().setOutgoingStatus(pair.getLeft(), BIP47Meta.STATUS_NOT_SENT);
                    }
                }

                if(!dragged)    {
                    strProgressMessage = BalanceActivity.this.getText(R.string.refresh_outgoing_notif_tx).toString();
                    publishProgress();
                }

                Intent intent = new Intent("com.samourai.wallet.BalanceActivity.RESTART_SERVICE");
                LocalBroadcastManager.getInstance(BalanceActivity.this).sendBroadcast(intent);
            }

            if(!dragged)    {

                if(PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.GUID_V, 0) < 4)    {
                    Log.i("BalanceActivity", "guid_v < 4");
                    try {
                        String _guid = AccessFactory.getInstance(BalanceActivity.this).createGUID();
                        String _hash = AccessFactory.getInstance(BalanceActivity.this).getHash(_guid, new CharSequenceX(AccessFactory.getInstance(BalanceActivity.this).getPIN()), AESUtil.DefaultPBKDF2Iterations);

                        PayloadUtil.getInstance(BalanceActivity.this).saveWalletToJSON(new CharSequenceX(_guid + AccessFactory.getInstance().getPIN()));

                        PrefsUtil.getInstance(BalanceActivity.this).setValue(PrefsUtil.ACCESS_HASH, _hash);
                        PrefsUtil.getInstance(BalanceActivity.this).setValue(PrefsUtil.ACCESS_HASH2, _hash);

                        Log.i("BalanceActivity", "guid_v == 4");
                    }
                    catch(MnemonicException.MnemonicLengthException | IOException | JSONException | DecryptionException e) {
                        ;
                    }
                }
                else if(!launch)    {
                    try {
                        PayloadUtil.getInstance(BalanceActivity.this).saveWalletToJSON(new CharSequenceX(AccessFactory.getInstance(BalanceActivity.this).getGUID() + AccessFactory.getInstance(BalanceActivity.this).getPIN()));
                    }
                    catch(Exception e) {

                    }
                }
                else    {
                    ;
                }

            }

            return "OK";
        }

        @Override
        protected void onPostExecute(String result) {

            if(!dragged)    {
                if(progress != null && progress.isShowing())    {
                    progress.dismiss();
                }
            }

//            bccForkThread();

        }

        @Override
        protected void onProgressUpdate(Void... values) {

            if(!dragged)    {
                progress.setTitle(strProgressTitle);
                progress.setMessage(strProgressMessage);
            }

        }

    }

    private class PoWTask extends AsyncTask<String, Void, String> {

        private boolean isOK = true;
        private String strBlockHash = null;

        @Override
        protected String doInBackground(String... params) {

            strBlockHash = params[0];

            JSONRPC jsonrpc = new JSONRPC(TrustedNodeUtil.getInstance().getUser(), TrustedNodeUtil.getInstance().getPassword(), TrustedNodeUtil.getInstance().getNode(), TrustedNodeUtil.getInstance().getPort());
            JSONObject nodeObj = jsonrpc.getBlockHeader(strBlockHash);
            if(nodeObj != null && nodeObj.has("hash"))    {
                PoW pow = new PoW(strBlockHash);
                String hash = pow.calcHash(nodeObj);
                if(hash != null && hash.toLowerCase().equals(strBlockHash.toLowerCase()))    {

                    JSONObject headerObj = APIFactory.getInstance(BalanceActivity.this).getBlockHeader(strBlockHash);
                    if(headerObj != null && headerObj.has(""))    {
                        if(!pow.check(headerObj, nodeObj, hash))    {
                            isOK = false;
                        }
                    }

                }
                else    {
                    isOK = false;
                }
            }

            return "OK";
        }

        @Override
        protected void onPostExecute(String result) {

            if(!isOK)    {

                new AlertDialog.Builder(BalanceActivity.this)
                        .setTitle(R.string.app_name)
                        .setMessage(getString(R.string.trusted_node_pow_failed) + "\n" + "Block hash:" + strBlockHash)
                        .setCancelable(false)
                        .setPositiveButton(R.string.close, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {

                                dialog.dismiss();

                            }
                        }).show();

            }

        }

        @Override
        protected void onPreExecute() {
            ;
        }

    }

    private class CPFPTask extends AsyncTask<String, Void, String> {

        private List<UTXO> utxos = null;
        private Handler handler = null;

        @Override
        protected void onPreExecute() {
            handler = new Handler();
            utxos = APIFactory.getInstance(BalanceActivity.this).getUtxos();
        }

        @Override
        protected String doInBackground(String... params) {

            Looper.prepare();

            Log.d("BalanceActivity", "hash:" + params[0]);

            JSONObject txObj = APIFactory.getInstance(BalanceActivity.this).getTxInfo(params[0]);
            if(txObj.has("inputs") && txObj.has("outputs"))    {

                final SuggestedFee suggestedFee = FeeUtil.getInstance().getSuggestedFee();

                try {
                    JSONArray inputs = txObj.getJSONArray("inputs");
                    JSONArray outputs = txObj.getJSONArray("outputs");

                    FeeUtil.getInstance().setSuggestedFee(FeeUtil.getInstance().getHighFee());
                    BigInteger estimatedFee = FeeUtil.getInstance().estimatedFee(inputs.length(), outputs.length());

                    long total_inputs = 0L;
                    long total_outputs = 0L;
                    long fee = 0L;

                    UTXO utxo = null;

                    for(int i = 0; i < inputs.length(); i++)   {
                        JSONObject obj = inputs.getJSONObject(i);
                        if(obj.has("outpoint"))    {
                            JSONObject objPrev = obj.getJSONObject("outpoint");
                            if(objPrev.has("value"))    {
                                total_inputs += objPrev.getLong("value");
                            }
                        }
                    }

                    for(int i = 0; i < outputs.length(); i++)   {
                        JSONObject obj = outputs.getJSONObject(i);
                        if(obj.has("value"))    {
                            total_outputs += obj.getLong("value");

                            String addr = obj.getString("address");
                            Log.d("BalanceActivity", "checking address:" + addr);
                            if(utxo == null)    {
                                utxo = getUTXO(addr);
                            }
                        }
                    }

                    boolean feeWarning = false;
                    fee = total_inputs - total_outputs;
                    if(fee > estimatedFee.longValue())    {
                        feeWarning = true;
                    }

                    Log.d("BalanceActivity", "total inputs:" + total_inputs);
                    Log.d("BalanceActivity", "total outputs:" + total_outputs);
                    Log.d("BalanceActivity", "fee:" + fee);
                    Log.d("BalanceActivity", "estimated fee:" + estimatedFee.longValue());
                    Log.d("BalanceActivity", "fee warning:" + feeWarning);
                    if(utxo != null)    {
                        Log.d("BalanceActivity", "utxo found");

                        List<UTXO> selectedUTXO = new ArrayList<UTXO>();
                        selectedUTXO.add(utxo);
                        int selected = utxo.getOutpoints().size();

                        long remainingFee = (estimatedFee.longValue() > fee) ? estimatedFee.longValue() - fee : 0L;
                        Log.d("BalanceActivity", "remaining fee:" + remainingFee);
                        int receiveIdx = AddressFactory.getInstance(BalanceActivity.this).getHighestTxReceiveIdx(0);
                        Log.d("BalanceActivity", "receive index:" + receiveIdx);
                        final String ownReceiveAddr = AddressFactory.getInstance(BalanceActivity.this).get(AddressFactory.RECEIVE_CHAIN).getAddressString();
                        Log.d("BalanceActivity", "receive address:" + ownReceiveAddr);

                        long totalAmount = utxo.getValue();
                        Log.d("BalanceActivity", "amount before fee:" + totalAmount);
                        BigInteger cpfpFee = FeeUtil.getInstance().estimatedFee(selected, 1);
                        Log.d("BalanceActivity", "cpfp fee:" + cpfpFee.longValue());

                        if(totalAmount < (cpfpFee.longValue() + remainingFee)) {
                            Log.d("BalanceActivity", "selecting additional utxo");
                            Collections.sort(utxos, new UTXO.UTXOComparator());
                            for(UTXO _utxo : utxos)   {
                                totalAmount += _utxo.getValue();
                                selectedUTXO.add(_utxo);
                                selected += _utxo.getOutpoints().size();
                                cpfpFee = FeeUtil.getInstance().estimatedFee(selected, 1);
                                if(totalAmount > (cpfpFee.longValue() + remainingFee + SamouraiWallet.bDust.longValue())) {
                                    break;
                                }
                            }
                            if(totalAmount < (cpfpFee.longValue() + remainingFee + SamouraiWallet.bDust.longValue())) {
                                handler.post(new Runnable() {
                                    public void run() {
                                        Toast.makeText(BalanceActivity.this, R.string.insufficient_funds, Toast.LENGTH_SHORT).show();
                                    }
                                });
                                FeeUtil.getInstance().setSuggestedFee(suggestedFee);
                                return "KO";
                            }
                        }

                        cpfpFee = cpfpFee.add(BigInteger.valueOf(remainingFee));
                        Log.d("BalanceActivity", "cpfp fee:" + cpfpFee.longValue());

                        final List<MyTransactionOutPoint> outPoints = new ArrayList<MyTransactionOutPoint>();
                        for(UTXO u : selectedUTXO)   {
                            outPoints.addAll(u.getOutpoints());
                        }

                        long _totalAmount = 0L;
                        for(MyTransactionOutPoint outpoint : outPoints)   {
                            _totalAmount += outpoint.getValue().longValue();
                        }
                        Log.d("BalanceActivity", "checked total amount:" + _totalAmount);
                        assert(_totalAmount == totalAmount);

                        long amount = totalAmount - cpfpFee.longValue();
                        Log.d("BalanceActivity", "amount after fee:" + amount);

                        if(amount < SamouraiWallet.bDust.longValue())    {
                            Log.d("BalanceActivity", "dust output");
                            Toast.makeText(BalanceActivity.this, R.string.cannot_output_dust, Toast.LENGTH_SHORT).show();
                        }

                        final HashMap<String, BigInteger> receivers = new HashMap<String, BigInteger>();
                        receivers.put(ownReceiveAddr, BigInteger.valueOf(amount));

                        String message = "";
                        if(feeWarning)  {
                            message += BalanceActivity.this.getString(R.string.fee_bump_not_necessary);
                            message += "\n\n";
                        }
                        message += BalanceActivity.this.getString(R.string.bump_fee) + " " + Coin.valueOf(remainingFee).toPlainString() + " BTC";

                        AlertDialog.Builder dlg = new AlertDialog.Builder(BalanceActivity.this)
                                .setTitle(R.string.app_name)
                                .setMessage(message)
                                .setCancelable(false)
                                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {

                                                if(AppUtil.getInstance(BalanceActivity.this.getApplicationContext()).isServiceRunning(WebSocketService.class)) {
                                                    stopService(new Intent(BalanceActivity.this.getApplicationContext(), WebSocketService.class));
                                                }
                                                startService(new Intent(BalanceActivity.this.getApplicationContext(), WebSocketService.class));

                                                Transaction tx = SendFactory.getInstance(BalanceActivity.this).makeTransaction(0, outPoints, receivers);
                                                if(tx != null)    {
                                                    tx = SendFactory.getInstance(BalanceActivity.this).signTransaction(tx);
                                                    final String hexTx = new String(Hex.encode(tx.bitcoinSerialize()));
                                                    Log.d("BalanceActivity", hexTx);

                                                    final String strTxHash = tx.getHashAsString();
                                                    Log.d("BalanceActivity", strTxHash);

                                                    boolean isOK = false;
                                                    try {

                                                        isOK = PushTx.getInstance(BalanceActivity.this).pushTx(hexTx);

                                                        if(isOK)    {

                                                            handler.post(new Runnable() {
                                                                public void run() {
                                                                    Toast.makeText(BalanceActivity.this, R.string.cpfp_spent, Toast.LENGTH_SHORT).show();

                                                                    FeeUtil.getInstance().setSuggestedFee(suggestedFee);

                                                                    Intent _intent = new Intent(BalanceActivity.this, MainActivity2.class);
                                                                    _intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                                                    startActivity(_intent);
                                                                }
                                                            });

                                                        }
                                                        else    {
                                                            handler.post(new Runnable() {
                                                                public void run() {
                                                                    Toast.makeText(BalanceActivity.this, R.string.tx_failed, Toast.LENGTH_SHORT).show();
                                                                }
                                                            });

                                                            // reset receive index upon tx fail
                                                            int prevIdx = HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(0).getReceive().getAddrIdx() - 1;
                                                            HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(0).getReceive().setAddrIdx(prevIdx);
                                                        }
                                                    }
                                                    catch(MnemonicException.MnemonicLengthException | DecoderException | IOException e) {
                                                        handler.post(new Runnable() {
                                                            public void run() {
                                                                Toast.makeText(BalanceActivity.this, "pushTx:" + e.getMessage(), Toast.LENGTH_SHORT).show();
                                                            }
                                                        });
                                                    }
                                                    finally {
                                                        ;
                                                    }

                                                }

                                    }
                                }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {

                                        try {
                                            // reset receive index upon tx fail
                                            int prevIdx = HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(0).getReceive().getAddrIdx() - 1;
                                            HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(0).getReceive().setAddrIdx(prevIdx);
                                        }
                                        catch(MnemonicException.MnemonicLengthException | DecoderException | IOException e) {
                                            handler.post(new Runnable() {
                                                public void run() {
                                                    Toast.makeText(BalanceActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                                                }
                                            });
                                        }
                                        finally {
                                            dialog.dismiss();
                                        }

                                    }
                                });
                        if(!isFinishing())    {
                            dlg.show();
                        }

                    }
                    else    {
                        handler.post(new Runnable() {
                            public void run() {
                                Toast.makeText(BalanceActivity.this, R.string.cannot_create_cpfp, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                }
                catch(final JSONException je) {
                    handler.post(new Runnable() {
                        public void run() {
                            Toast.makeText(BalanceActivity.this, "cpfp:" + je.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                FeeUtil.getInstance().setSuggestedFee(suggestedFee);

            }
            else    {
                handler.post(new Runnable() {
                    public void run() {
                        Toast.makeText(BalanceActivity.this, R.string.cpfp_cannot_retrieve_tx, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            Looper.loop();

            return "OK";
        }

        @Override
        protected void onPostExecute(String result) {
            ;
        }

        @Override
        protected void onProgressUpdate(Void... values) {
            ;
        }

        private UTXO getUTXO(String address)    {

            UTXO ret = null;
            int idx = -1;

            for(int i = 0; i < utxos.size(); i++)  {
                UTXO utxo = utxos.get(i);
                Log.d("BalanceActivity", "utxo address:" + utxo.getOutpoints().get(0).getAddress());
                if(utxo.getOutpoints().get(0).getAddress().equals(address))    {
                    ret = utxo;
                    idx = i;
                    break;
                }
            }

            if(ret != null)    {
                utxos.remove(idx);
                return ret;
            }

            return null;
        }

    }

    private class RBFTask extends AsyncTask<String, Void, String> {

        private List<UTXO> utxos = null;
        private Handler handler = null;
        private RBFSpend rbf = null;

        @Override
        protected void onPreExecute() {
            handler = new Handler();
            utxos = APIFactory.getInstance(BalanceActivity.this).getUtxos();
        }

        @Override
        protected String doInBackground(final String... params) {

            Looper.prepare();

            Log.d("BalanceActivity", "hash:" + params[0]);

            rbf = RBFUtil.getInstance().get(params[0]);
            Log.d("BalanceActivity", "rbf:" + ((rbf == null) ? "null" : "not null"));
            final Transaction tx = new Transaction(MainNetParams.get(), Hex.decode(rbf.getSerializedTx()));
            Log.d("BalanceActivity", "tx serialized:" + rbf.getSerializedTx());
            Log.d("BalanceActivity", "tx inputs:" + tx.getInputs().size());
            Log.d("BalanceActivity", "tx outputs:" + tx.getOutputs().size());
            JSONObject txObj = APIFactory.getInstance(BalanceActivity.this).getTxInfo(params[0]);
            if(tx != null && txObj.has("inputs") && txObj.has("outputs"))    {
                try {
                    JSONArray inputs = txObj.getJSONArray("inputs");
                    JSONArray outputs = txObj.getJSONArray("outputs");

                    SuggestedFee suggestedFee = FeeUtil.getInstance().getSuggestedFee();
                    FeeUtil.getInstance().setSuggestedFee(FeeUtil.getInstance().getHighFee());
                    BigInteger estimatedFee = FeeUtil.getInstance().estimatedFee(tx.getInputs().size(), tx.getOutputs().size());

                    long total_inputs = 0L;
                    long total_outputs = 0L;
                    long fee = 0L;
                    long total_change = 0L;
                    List<String> selfAddresses = new ArrayList<String>();

                    for(int i = 0; i < inputs.length(); i++)   {
                        JSONObject obj = inputs.getJSONObject(i);
                        if(obj.has("outpoint"))    {
                            JSONObject objPrev = obj.getJSONObject("outpoint");
                            if(objPrev.has("value"))    {
                                total_inputs += objPrev.getLong("value");
                            }
                        }
                    }

                    for(int i = 0; i < outputs.length(); i++)   {
                        JSONObject obj = outputs.getJSONObject(i);
                        if(obj.has("value"))    {
                            total_outputs += obj.getLong("value");

                            String _addr = obj.getString("address");
                            selfAddresses.add(_addr);
                            if(_addr != null && rbf.getChangeAddrs().contains(_addr.toString()))    {
                                total_change += obj.getLong("value");
                            }
                        }
                    }

                    boolean feeWarning = false;
                    fee = total_inputs - total_outputs;
                    if(fee > estimatedFee.longValue())    {
                        feeWarning = true;
                    }

                    long remainingFee = (estimatedFee.longValue() > fee) ? estimatedFee.longValue() - fee : 0L;

                    Log.d("BalanceActivity", "total inputs:" + total_inputs);
                    Log.d("BalanceActivity", "total outputs:" + total_outputs);
                    Log.d("BalanceActivity", "total change:" + total_change);
                    Log.d("BalanceActivity", "fee:" + fee);
                    Log.d("BalanceActivity", "estimated fee:" + estimatedFee.longValue());
                    Log.d("BalanceActivity", "fee warning:" + feeWarning);
                    Log.d("BalanceActivity", "remaining fee:" + remainingFee);

                    List<TransactionOutput> txOutputs = new ArrayList<TransactionOutput>();
                    txOutputs.addAll(tx.getOutputs());

                    long remainder = remainingFee;
                    if(total_change > remainder)    {
                        for(TransactionOutput output : txOutputs)   {
                            if(rbf.getChangeAddrs().contains(output.getAddressFromP2PKHScript(MainNetParams.get()).toString()))    {
                                if(output.getValue().longValue() >= (remainder + SamouraiWallet.bDust.longValue()))    {
                                    output.setValue(Coin.valueOf(output.getValue().longValue() - remainder));
                                    remainder = 0L;
                                    break;
                                }
                                else    {
                                    remainder -= output.getValue().longValue();
                                    output.setValue(Coin.valueOf(0L));      // output will be discarded later
                                }
                            }

                        }

                    }

                    //
                    // original inputs are not modified
                    //
                    List<MyTransactionInput> _inputs = new ArrayList<MyTransactionInput>();
                    List<TransactionInput> txInputs = tx.getInputs();
                    for(TransactionInput input : txInputs) {
                        MyTransactionInput _input = new MyTransactionInput(MainNetParams.get(), null, new byte[0], input.getOutpoint(), input.getOutpoint().getHash().toString(), (int)input.getOutpoint().getIndex());
                        _input.setSequenceNumber(SamouraiWallet.RBF_SEQUENCE_NO);
                        _inputs.add(_input);
                        Log.d("BalanceActivity", "add outpoint:" + _input.getOutpoint().toString());
                    }

                    if(remainder > 0L)    {
                        List<UTXO> selectedUTXO = new ArrayList<UTXO>();
                        long selectedAmount = 0L;
                        int selected = 0;
                        long _remainingFee = remainder;
                        Collections.sort(utxos, new UTXO.UTXOComparator());
                        for(UTXO _utxo : utxos)   {

                            Log.d("BalanceActivity", "utxo value:" + _utxo.getValue());

                            //
                            // do not select utxo that are change outputs in current rbf tx
                            //
                            boolean isChange = false;
                            boolean isSelf = false;
                            for(MyTransactionOutPoint outpoint : _utxo.getOutpoints())  {
                                if(rbf.containsChangeAddr(outpoint.getAddress()))    {
                                    Log.d("BalanceActivity", "is change:" + outpoint.getAddress());
                                    Log.d("BalanceActivity", "is change:" + outpoint.getValue().longValue());
                                    isChange = true;
                                    break;
                                }
                                if(selfAddresses.contains(outpoint.getAddress()))    {
                                    Log.d("BalanceActivity", "is self:" + outpoint.getAddress());
                                    Log.d("BalanceActivity", "is self:" + outpoint.getValue().longValue());
                                    isSelf = true;
                                    break;
                                }
                            }
                            if(isChange || isSelf)    {
                                continue;
                            }

                            selectedUTXO.add(_utxo);
                            selected += _utxo.getOutpoints().size();
                            Log.d("BalanceActivity", "selected utxo:" + selected);
                            selectedAmount += _utxo.getValue();
                            Log.d("BalanceActivity", "selected utxo value:" + _utxo.getValue());
                            _remainingFee = FeeUtil.getInstance().estimatedFee(inputs.length() + selected, outputs.length() == 1 ? 2 : outputs.length()).longValue();
                            Log.d("BalanceActivity", "_remaining fee:" + _remainingFee);
                            if(selectedAmount >= (_remainingFee + SamouraiWallet.bDust.longValue())) {
                                break;
                            }
                        }
                        long extraChange = 0L;
                        if(selectedAmount < (_remainingFee + SamouraiWallet.bDust.longValue())) {
                            handler.post(new Runnable() {
                                public void run() {
                                    Toast.makeText(BalanceActivity.this, R.string.insufficient_funds, Toast.LENGTH_SHORT).show();
                                }
                            });
                            return "KO";
                        }
                        else    {
                            extraChange = selectedAmount - _remainingFee;
                            Log.d("BalanceActivity", "extra change:" + extraChange);
                        }

                        boolean addedChangeOutput = false;
                        // parent tx didn't have change output
                        if(outputs.length() == 1 && extraChange > 0L)    {
                            try {
                                int changeIdx = HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(0).getChange().getAddrIdx();
                                String change_address = HD_WalletFactory.getInstance(BalanceActivity.this).get().getAccount(0).getChange().getAddressAt(changeIdx).getAddressString();

                                Script toOutputScript = ScriptBuilder.createOutputScript(org.bitcoinj.core.Address.fromBase58(MainNetParams.get(), change_address));
                                TransactionOutput output = new TransactionOutput(MainNetParams.get(), null, Coin.valueOf(extraChange), toOutputScript.getProgram());
                                txOutputs.add(output);
                                addedChangeOutput = true;
                            }
                            catch(MnemonicException.MnemonicLengthException | IOException e) {
                                handler.post(new Runnable() {
                                    public void run() {
                                        Toast.makeText(BalanceActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                                        Toast.makeText(BalanceActivity.this, R.string.cannot_create_change_output, Toast.LENGTH_SHORT).show();
                                    }
                                });
                                return "KO";
                            }

                        }
                        // parent tx had change output
                        else    {
                            for(TransactionOutput output : txOutputs)   {
                                Log.d("BalanceActivity", "checking for change:" + output.getAddressFromP2PKHScript(MainNetParams.get()).toString());
                                if(rbf.containsChangeAddr(output.getAddressFromP2PKHScript(MainNetParams.get()).toString()))    {
                                    Log.d("BalanceActivity", "before extra:" + output.getValue().longValue());
                                    output.setValue(Coin.valueOf(extraChange + output.getValue().longValue()));
                                    Log.d("BalanceActivity", "after extra:" + output.getValue().longValue());
                                    addedChangeOutput = true;
                                    break;
                                }
                            }
                        }

                        // sanity check
                        if(extraChange > 0L && !addedChangeOutput)    {
                            handler.post(new Runnable() {
                                public void run() {
                                    Toast.makeText(BalanceActivity.this, R.string.cannot_create_change_output, Toast.LENGTH_SHORT).show();
                                }
                            });
                            return "KO";
                        }

                        //
                        // update keyBag w/ any new paths
                        //
                        final HashMap<String,String> keyBag = rbf.getKeyBag();
                        for(UTXO _utxo : selectedUTXO)    {

                            for(MyTransactionOutPoint outpoint : _utxo.getOutpoints()) {

                                MyTransactionInput _input = new MyTransactionInput(MainNetParams.get(), null, new byte[0], outpoint, outpoint.getTxHash().toString(), outpoint.getTxOutputN());
                                _input.setSequenceNumber(SamouraiWallet.RBF_SEQUENCE_NO);
                                _inputs.add(_input);
                                Log.d("BalanceActivity", "add selected outpoint:" + _input.getOutpoint().toString());

                                String path = APIFactory.getInstance(BalanceActivity.this).getUnspentPaths().get(outpoint.getAddress());
                                if(path != null)    {
                                    rbf.addKey(outpoint.toString(), path);
                                }
                                else    {
                                    String pcode = BIP47Meta.getInstance().getPCode4Addr(outpoint.getAddress());
                                    int idx = BIP47Meta.getInstance().getIdx4Addr(outpoint.getAddress());
                                    rbf.addKey(outpoint.toString(), pcode + "/" + idx);
                                }

                            }

                        }
                        rbf.setKeyBag(keyBag);

                    }

                    //
                    // BIP69 sort of outputs/inputs
                    //
                    final Transaction _tx = new Transaction(MainNetParams.get());
                    List<TransactionOutput> _txOutputs = new ArrayList<TransactionOutput>();
                    _txOutputs.addAll(txOutputs);
                    Collections.sort(_txOutputs, new SendFactory.BIP69OutputComparator());
                    for(TransactionOutput to : _txOutputs) {
                        // zero value outputs discarded here
                        if(to.getValue().longValue() > 0L)    {
                            _tx.addOutput(to);
                        }
                    }

                    List<MyTransactionInput> __inputs = new ArrayList<MyTransactionInput>();
                    __inputs.addAll(_inputs);
                    Collections.sort(__inputs, new SendFactory.BIP69InputComparator());
                    for(TransactionInput input : __inputs) {
                        _tx.addInput(input);
                    }

                    FeeUtil.getInstance().setSuggestedFee(suggestedFee);

                    String message = "";
                    if(feeWarning)  {
                        message += BalanceActivity.this.getString(R.string.fee_bump_not_necessary);
                        message += "\n\n";
                    }
                    message += BalanceActivity.this.getString(R.string.bump_fee) + " " + Coin.valueOf(remainingFee).toPlainString() + " BTC";

                    AlertDialog.Builder dlg = new AlertDialog.Builder(BalanceActivity.this)
                            .setTitle(R.string.app_name)
                            .setMessage(message)
                            .setCancelable(false)
                            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {

                                    Transaction __tx = signTx(_tx);
                                    final String hexTx = new String(Hex.encode(__tx.bitcoinSerialize()));
                                    Log.d("BalanceActivity", hexTx);

                                    final String strTxHash = __tx.getHashAsString();
                                    Log.d("BalanceActivity", strTxHash);

                                    if(__tx != null)    {

                                        boolean isOK = false;
                                        try {

                                            isOK = PushTx.getInstance(BalanceActivity.this).pushTx(hexTx);

                                            if(isOK)    {

                                                handler.post(new Runnable() {
                                                    public void run() {
                                                        Toast.makeText(BalanceActivity.this, R.string.rbf_spent, Toast.LENGTH_SHORT).show();

                                                        RBFSpend _rbf = rbf;    // includes updated 'keyBag'
                                                        _rbf.setSerializedTx(hexTx);
                                                        _rbf.setHash(strTxHash);
                                                        _rbf.setPrevHash(params[0]);
                                                        RBFUtil.getInstance().add(_rbf);

                                                        Intent _intent = new Intent(BalanceActivity.this, MainActivity2.class);
                                                        _intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                                        startActivity(_intent);
                                                    }
                                                });

                                            }
                                            else    {

                                                handler.post(new Runnable() {
                                                    public void run() {
                                                        Toast.makeText(BalanceActivity.this, R.string.tx_failed, Toast.LENGTH_SHORT).show();
                                                    }
                                                });

                                            }
                                        }
                                        catch(final DecoderException de) {
                                            handler.post(new Runnable() {
                                                public void run() {
                                                    Toast.makeText(BalanceActivity.this, "pushTx:" + de.getMessage(), Toast.LENGTH_SHORT).show();
                                                }
                                            });
                                        }
                                        finally {
                                            ;
                                        }

                                    }

                                }
                            }).setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {

                                    dialog.dismiss();

                                }
                            });
                    if(!isFinishing())    {
                        dlg.show();
                    }

                }
                catch(final JSONException je) {
                    handler.post(new Runnable() {
                        public void run() {
                            Toast.makeText(BalanceActivity.this, "rbf:" + je.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }

            }
            else    {
                Toast.makeText(BalanceActivity.this, R.string.cpfp_cannot_retrieve_tx, Toast.LENGTH_SHORT).show();
            }

            Looper.loop();

            return "OK";
        }

        @Override
        protected void onPostExecute(String result) {
            ;
        }

        @Override
        protected void onProgressUpdate(Void... values) {
            ;
        }

        private Transaction signTx(Transaction tx)    {

            HashMap<String,ECKey> keyBag = new HashMap<String,ECKey>();

            HashMap<String,String> keys = rbf.getKeyBag();
            for(String outpoint : keys.keySet())   {

                ECKey ecKey = null;

                String[] s = keys.get(outpoint).split("/");
                if(s.length == 3)    {
                    HD_Address hd_address = AddressFactory.getInstance(BalanceActivity.this).get(0, Integer.parseInt(s[1]), Integer.parseInt(s[2]));
                    String strPrivKey = hd_address.getPrivateKeyString();
                    DumpedPrivateKey pk = new DumpedPrivateKey(MainNetParams.get(), strPrivKey);
                    ecKey = pk.getKey();
                }
                else if(s.length == 2)    {
                    try {
                        PaymentAddress address = BIP47Util.getInstance(BalanceActivity.this).getReceiveAddress(new PaymentCode(s[0]), Integer.parseInt(s[1]));
                        ecKey = address.getReceiveECKey();
                    }
                    catch(Exception e) {
                        ;
                    }
                }
                else    {
                    ;
                }

                Log.i("BalanceActivity", "outpoint:" + outpoint);
                Log.i("BalanceActivity", "ECKey address from ECKey:" + ecKey.toAddress(MainNetParams.get()).toString());

                if(ecKey != null) {
                    keyBag.put(outpoint, ecKey);
                }
                else {
                    throw new RuntimeException("ECKey error: cannot process private key");
//                    Log.i("ECKey error", "cannot process private key");
                }

            }

            List<TransactionInput> inputs = tx.getInputs();
            for (int i = 0; i < inputs.size(); i++) {

                ECKey ecKey = keyBag.get(inputs.get(i).getOutpoint().toString());
                Log.i("BalanceActivity", "sign outpoint:" + inputs.get(i).getOutpoint().toString());
                Log.i("BalanceActivity", "ECKey address from keyBag:" + ecKey.toAddress(MainNetParams.get()).toString());

                Log.i("BalanceActivity", "script:" + ScriptBuilder.createOutputScript(ecKey.toAddress(MainNetParams.get())));
                Log.i("BalanceActivity", "script:" + Hex.toHexString(ScriptBuilder.createOutputScript(ecKey.toAddress(MainNetParams.get())).getProgram()));
                TransactionSignature sig = tx.calculateSignature(i, ecKey, ScriptBuilder.createOutputScript(ecKey.toAddress(MainNetParams.get())).getProgram(), Transaction.SigHash.ALL, false);
                tx.getInput(i).setScriptSig(ScriptBuilder.createInputScript(sig, ecKey));

            }

            return tx;
        }

    }

    private void bccForkThread()    {

        final Handler handler = new Handler();

        new Thread(new Runnable() {
            @Override
            public void run() {

                Looper.prepare();

                boolean isFork = false;
                int cf = -1;
                boolean dismissed = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BCC_DISMISSED, false);

                if(!dismissed)    {
                    long latestBlockHeight = APIFactory.getInstance(BalanceActivity.this).getLatestBlockHeight();
                    long distance = 0L;

                    String strForkStatus = HardForkUtil.getInstance(BalanceActivity.this).forkStatus();
                    try {
                        JSONObject forkObj = new JSONObject(strForkStatus);
                        if(forkObj != null && forkObj.has("forks") && forkObj.getJSONObject("forks").has("abc"))    {
                            JSONObject abcObj = forkObj.getJSONObject("forks").getJSONObject("abc");
                            if(abcObj.has("height"))    {


                                long height = abcObj.getLong("height");
                                distance = latestBlockHeight - height;

                                boolean laterThanFork = true;
                                List<UTXO> utxos = APIFactory.getInstance(BalanceActivity.this).getUtxos();
                                for(UTXO utxo : utxos)   {
                                    List<MyTransactionOutPoint> outpoints = utxo.getOutpoints();
                                    for(MyTransactionOutPoint outpoint : outpoints)   {
                                        if(outpoint.getConfirmations() >= distance)    {
                                            laterThanFork = false;
                                            break;
                                        }
                                    }
                                    if(!laterThanFork)    {
                                        break;
                                    }
                                }

                                if(laterThanFork)    {
                                    dismissed = true;
                                    PrefsUtil.getInstance(BalanceActivity.this).setValue(PrefsUtil.BCC_DISMISSED, true);
                                }

                            }

                        }

                    }
                    catch(JSONException je) {
                        ;
                    }

                }

                if(!dismissed && HardForkUtil.getInstance(BalanceActivity.this).isBitcoinABCForkActivateTime())    {

                    String status = HardForkUtil.getInstance(BalanceActivity.this).forkStatus();
                    Log.d("BalanceActivity", status);
                    try {
                        JSONObject statusObj = new JSONObject(status);
                        if(statusObj.has("forks") && statusObj.getJSONObject("forks").has("abc") &&
                                statusObj.has("clients") && statusObj.getJSONObject("clients").has("abc") &&
                                statusObj.getJSONObject("clients").getJSONObject("abc").has("replay") &&
                                statusObj.getJSONObject("clients").getJSONObject("abc").getBoolean("replay") == true)   {

                            isFork = true;

                            String strTxHash = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BCC_REPLAY1, "");
                            if(strTxHash != null && strTxHash.length() > 0)    {

                                Log.d("BalanceActivity", "rbf replay hash:" + strTxHash);

                                JSONObject txObj = APIFactory.getInstance(BalanceActivity.this).getTxInfo(strTxHash);
                                final int latestBlockHeight = (int)APIFactory.getInstance(BalanceActivity.this).getLatestBlockHeight();

                                Log.d("BalanceActivity", "txObj:" + txObj.toString());

                                try {
                                    if(txObj != null && txObj.has("block"))    {
                                        JSONObject blockObj = txObj.getJSONObject("block");
                                        if(blockObj.has("height") && blockObj.getInt("height") > 0)    {
                                            int blockHeight = blockObj.getInt("height");
                                            cf = (latestBlockHeight - blockHeight) + 1;
                                            Log.d("BalanceActivity", "confirmations (block):" + cf);
                                            if(cf >= 6)    {
                                                isFork = false;
                                            }
                                        }

                                    }
                                    else if(txObj != null && txObj.has("txid"))    {
                                        cf = 0;
                                        Log.d("BalanceActivity", "confirmations (hash):" + cf);
                                    }
                                    else    {
                                        ;
                                    }

                                }
                                catch(JSONException je) {
                                    ;
                                }

                            }

                        }
                    }
                    catch(JSONException je) {
                        ;
                    }

                }

                final int COLOR_ORANGE = 0xfffb8c00;
                final int COLOR_GREEN = 0xff4caf50;

                final boolean bccReplayed = PrefsUtil.getInstance(BalanceActivity.this).getValue(PrefsUtil.BCC_REPLAYED, false);

                if(cf >= 0 && cf < 6)   {
                    handler.post(new Runnable() {
                        public void run() {
                            layoutAlert = (LinearLayout)findViewById(R.id.alert);
                            layoutAlert.setBackgroundColor(COLOR_ORANGE);
                            TextView tvLeftTop = (TextView)layoutAlert.findViewById(R.id.left_top);
                            TextView tvLeftBottom = (TextView)layoutAlert.findViewById(R.id.left_bottom);
                            TextView tvRight = (TextView)layoutAlert.findViewById(R.id.right);
                            tvLeftTop.setText(getText(R.string.replay_chain_split));
                            tvLeftBottom.setText(getText(R.string.replay_in_progress));
                            tvRight.setText(getText(R.string.replay_info));
                            layoutAlert.setVisibility(View.VISIBLE);
                            layoutAlert.setOnTouchListener(new View.OnTouchListener() {
                                @Override
                                public boolean onTouch(View v, MotionEvent event) {
                                    Intent intent = new Intent(BalanceActivity.this, ReplayProtectionActivity.class);
                                    startActivity(intent);
                                    return false;
                                }
                            });
                        }
                    });
                }
                else if(cf >= 6 && !bccReplayed)   {

                    PrefsUtil.getInstance(BalanceActivity.this).setValue(PrefsUtil.BCC_REPLAYED, true);

                    handler.post(new Runnable() {
                        public void run() {
                            layoutAlert = (LinearLayout)findViewById(R.id.alert);
                            layoutAlert.setBackgroundColor(COLOR_GREEN);
                            TextView tvLeftTop = (TextView)layoutAlert.findViewById(R.id.left_top);
                            TextView tvLeftBottom = (TextView)layoutAlert.findViewById(R.id.left_bottom);
                            TextView tvRight = (TextView)layoutAlert.findViewById(R.id.right);
                            tvLeftTop.setText(getText(R.string.replay_chain_split));
                            tvLeftBottom.setText(getText(R.string.replay_protected));
                            tvRight.setText(getText(R.string.ok));
                            layoutAlert.setVisibility(View.VISIBLE);
                        }
                    });
                }
                else if(bccReplayed)    {
                    handler.post(new Runnable() {
                        public void run() {
                            layoutAlert = (LinearLayout)findViewById(R.id.alert);
                            layoutAlert.setVisibility(View.GONE);
                        }
                    });
                }
                else if(isFork)    {
                    handler.post(new Runnable() {
                        public void run() {
                            layoutAlert = (LinearLayout)findViewById(R.id.alert);
                            TextView tvLeftTop = (TextView)layoutAlert.findViewById(R.id.left_top);
                            TextView tvLeftBottom = (TextView)layoutAlert.findViewById(R.id.left_bottom);
                            TextView tvRight = (TextView)layoutAlert.findViewById(R.id.right);
                            tvLeftTop.setText(getText(R.string.replay_chain_split));
                            tvLeftBottom.setText(getText(R.string.replay_enable));
                            tvRight.setText(getText(R.string.replay_info));
                            layoutAlert.setVisibility(View.VISIBLE);
                            layoutAlert.setOnTouchListener(new View.OnTouchListener() {
                                @Override
                                public boolean onTouch(View v, MotionEvent event) {
                                    Intent intent = new Intent(BalanceActivity.this, ReplayProtectionWarningActivity.class);
                                    startActivity(intent);
                                    return false;
                                }
                            });
                        }
                    });
                }
                else    {
                    handler.post(new Runnable() {
                        public void run() {
                            layoutAlert = (LinearLayout)findViewById(R.id.alert);
                            layoutAlert.setVisibility(View.GONE);
                        }
                    });
                }

                Looper.loop();

            }
        }).start();

    }

}
