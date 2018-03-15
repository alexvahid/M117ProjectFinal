/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothchat;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
//import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import com.example.android.common.logger.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;


/**
 * This fragment controls Bluetooth to communicate with other devices.
 */
public class BluetoothChatFragment extends Fragment {

    private static final String TAG = "BluetoothChatFragment";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;

    // Layout Views
    private ListView mConversationView;
    private EditText mOutEditText;
    private Button mSendButton;

    /**
     * Name of the connected device
     */
    private String mConnectedDeviceName = null;

    /**
     * Array adapter for the conversation thread
     */
    private ArrayAdapter<String> mConversationArrayAdapter;

    /**
     * String buffer for outgoing messages
     */
    private StringBuffer mOutStringBuffer;

    /**
     * Local Bluetooth adapter
     */
    private BluetoothAdapter mBluetoothAdapter = null;

    /**
     * Member object for the chat services
     */
    private BluetoothChatService mChatService = null;

    class AddressBookItem {

        AddressBookItem(String name, String secretKey) {
            this.name = name;
            this.secretKey = secretKey;
        }

        String name;
        String secretKey;
    }
    private Map<String, AddressBookItem> addressBook = new HashMap();

    private Map<String, ArrayAdapter<String>> conversations = new HashMap();
    private String currentConversation = null;

    private String zohar = "B4:BF:F6:CE:6F:A3";
    private String michael = "D0:51:62:42:D8:B6";
    private String phone25 = "44:80:EB:35:A5:A9";
    private String phone26 = "44:80:EB:35:A2:E2";
    private String phone38 = "44:80:EB:35:A3:9B";
    private String nexusS1 = "F0:08:F1:54:86:45";
    private String nexusS2 = "0C:DF:A4:B7:F7:50";
    private String thisAddress = null;

    private Map<String, String> routeTable = new HashMap();
    private Map<String, String> reverseRouteTable = new HashMap();
    private AdHocMessage queuedAdHocMessage = null;

    private int requestCounter = 0;
    private int sourceSequence = 0;

    public String encrypt(String plainData, String secretKeyString) throws Exception
    {
        //hash the secret key string to be sure that it is of length 256, which
        //the SecretKeySpec expects
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(secretKeyString.getBytes());
        SecretKey secretKey = new SecretKeySpec(hash, 0, hash.length, "AES");

        //create a cypher and encrypt the message to a new string using the secret key
        Cipher aesCipher = Cipher.getInstance("AES");
        aesCipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] byteDataToEncrypt = plainData.getBytes();
        byte[] byteCipherText = aesCipher.doFinal(byteDataToEncrypt);
        return Base64.encodeToString(byteCipherText, Base64.DEFAULT);
    }

    public String decrypt(String cipherData, String secretKeyString) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(secretKeyString.getBytes());
        SecretKey secretKey = new SecretKeySpec(hash, 0, hash.length, "AES");

        //create a cypher and decode the encrypted string to plain text using the secret key
        byte[] data = Base64.decode(cipherData, Base64.DEFAULT);
        Cipher aesCipher = Cipher.getInstance("AES");
        aesCipher.init(Cipher.DECRYPT_MODE, secretKey);
        byte[] plainData = aesCipher.doFinal(data);
        return new String(plainData);
    }

    public AdHocMessage createAdHocMessage(String message, String destinationAddress) {

        try {
            message = encrypt(message, addressBook.get(destinationAddress).secretKey);
        } catch (Exception e) {
            e.printStackTrace();
        }

        AdHocMessage adHocMessage = new AdHocMessage();

        adHocMessage.destinationAddress = destinationAddress;
        adHocMessage.sourceAddress = thisAddress;
        adHocMessage.message = message;
        adHocMessage.type = AdHocMessage.Type.ADHOC_MESSAGE;

        return adHocMessage;
    }

    public AdHocMessage createRouteReply(AdHocMessage routeRequest) {

        AdHocMessage routeReply = new AdHocMessage();

        routeReply.destinationAddress = routeRequest.sourceAddress;
        routeReply.sourceAddress = routeRequest.destinationAddress;
        routeReply.passingAddresses.add(thisAddress);
        routeReply.message = "route_reply";
        routeReply.requestID = routeRequest.requestID;
        routeReply.type = AdHocMessage.Type.ROUTE_REPLY;
        routeReply.hopCount = routeRequest.hopCount;
        routeReply.destinationSequenceNumber = sourceSequence;

        return routeReply;
    }

    public AdHocMessage createRouteRequest(String destinationAddress) {
        //create route request
        AdHocMessage routeRequest = new AdHocMessage();

        routeRequest.destinationAddress = destinationAddress;
        routeRequest.sourceAddress = thisAddress;
        routeRequest.passingAddresses.add(thisAddress);
        routeRequest.message = "route_request";
        routeRequest.requestID = ++requestCounter;
        routeRequest.type = AdHocMessage.Type.ROUTE_REQUEST;
        routeRequest.destinationSequenceNumber = 0;
        routeRequest.sourceSequenceNumber = ++sourceSequence;
        routeRequest.hopCount = 0;

        return routeRequest;
    }

    //figure out who to connect to to propagate this message
    public void sendAdHocMessage(AdHocMessage adHocMessage) {

        String addressToConnect = null;

        int sleepVal = 0;

        //end the connection
        //(connections only last long enough to send a single message)
        mChatService.stop();
        mChatService.start();

        //if this is the intended destination of the message
        if (adHocMessage.destinationAddress.equals(thisAddress)) {

            //if receiving a route request, create a route reply
            if (adHocMessage.type == AdHocMessage.Type.ROUTE_REQUEST) {

                //increment source sequence
                ++sourceSequence;

                //don't create a route reply if this isn't the first route request we've seen
                if (reverseRouteTable.containsKey(adHocMessage.sourceAddress + adHocMessage.requestID.toString())) {
                    Toast.makeText(getActivity(), "ROUTE_REQUEST ALREADY SEEN", Toast.LENGTH_SHORT).show();
                    return;
                }
                //create route reply and update route tables
                else {
                    reverseRouteTable.put(adHocMessage.sourceAddress + adHocMessage.requestID.toString(), adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1));
                    routeTable.put(adHocMessage.sourceAddress, adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1));
                }

                Toast.makeText(getActivity(), "CREATING ROUTE_REPLY", Toast.LENGTH_SHORT).show();
                AdHocMessage routeReply = createRouteReply(adHocMessage);
                sendAdHocMessage(routeReply);
                return;
            }

            //if receiving a route reply, send the queued message
            else if (adHocMessage.type == AdHocMessage.Type.ROUTE_REPLY) {

                //add info to route table and send the actual message
                routeTable.put(adHocMessage.sourceAddress, adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1));

                SystemClock.sleep(sleepVal);
                Toast.makeText(getActivity(), "PATH OBTAINED, SENDING MESSAGE", Toast.LENGTH_SHORT).show();
                sendAdHocMessage(queuedAdHocMessage);
                queuedAdHocMessage = null;
                return;
            }

            //if receiving a message
            else {

                AddressBookItem abi = addressBook.get(adHocMessage.sourceAddress);

                SystemClock.sleep(sleepVal);
                Toast.makeText(getActivity(), "NEW MESSAGE FROM " + abi.name, Toast.LENGTH_SHORT).show();

                try {
                    adHocMessage.message = decrypt(adHocMessage.message, abi.secretKey);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                String messageToDisplay = abi.name + ":  " + adHocMessage.message;

                if (currentConversation != null && currentConversation.equals(adHocMessage.sourceAddress)) {
                    mConversationArrayAdapter.add(messageToDisplay);
                } else {
                    if (conversations.containsKey(adHocMessage.sourceAddress)) {
                        conversations.get(adHocMessage.sourceAddress).add(messageToDisplay);

                    } else {
                        ArrayAdapter<String> newAdapter = new ArrayAdapter<String>(getActivity(), R.layout.message);
                        newAdapter.add(messageToDisplay);
                        conversations.put(adHocMessage.sourceAddress, newAdapter);
                    }
                }

                return;
            }
        }

        //if propagating a route request
        if (adHocMessage.type == AdHocMessage.Type.ROUTE_REQUEST) {

            Toast.makeText(getActivity(), "PROPAGATING ROUTE_REQUEST", Toast.LENGTH_SHORT).show();

            //increment source sequence counter
            ++sourceSequence;

            //STEP 1. source address and request ID are looked up in the local history table
            if (reverseRouteTable.containsKey(adHocMessage.sourceAddress + adHocMessage.requestID.toString())) {
                return;
            }

            //STEP 2. destinatation is looked up in the routing table
            if (routeTable.containsKey(adHocMessage.destinationAddress)) {

                //send a route reply back
                addressToConnect = adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1);

                reverseRouteTable.put(adHocMessage.sourceAddress + adHocMessage.requestID.toString(), adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1));
                routeTable.put(adHocMessage.sourceAddress, adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1));

                AdHocMessage routeReply = createRouteReply(adHocMessage);

                //queue and connect
                mChatService.queueAdHocMessage(routeReply);
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(addressToConnect);
                mChatService.connect(device, false);

                return;
            }

            //STEP 3. no route found, increment hop counter
            adHocMessage.hopCount++;


            //update reverse route table
            reverseRouteTable.put(adHocMessage.sourceAddress + adHocMessage.requestID.toString(), adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1));

            //make sure we don't send the route request back to its originator
            passingAddresses = adHocMessage.passingAddresses;

            //update passing address
            adHocMessage.passingAddresses.add(thisAddress);

            propagateRouteRequest(adHocMessage);

            return;
        }

        //if propagating a route reply
        else if (adHocMessage.type == AdHocMessage.Type.ROUTE_REPLY) {

            SystemClock.sleep(sleepVal);
            Toast.makeText(getActivity(), "PROPAGATING ROUTE_REPLY", Toast.LENGTH_SHORT).show();

            //add to route table
            routeTable.put(adHocMessage.sourceAddress, adHocMessage.passingAddresses.get(adHocMessage.passingAddresses.size()-1));

            //figure out where to connect next
            addressToConnect = reverseRouteTable.get(adHocMessage.destinationAddress + adHocMessage.requestID.toString());

            //add other direction to route table
            routeTable.put(adHocMessage.destinationAddress, addressToConnect);

            //update passing address
            adHocMessage.passingAddresses.add(thisAddress);

            //queue and connect
            mChatService.queueAdHocMessage(adHocMessage);
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(addressToConnect);
            mChatService.connect(device, false);

            return;
        }

        //if route to the destination is already known
        if (routeTable.containsKey(adHocMessage.destinationAddress)) {

            SystemClock.sleep(sleepVal);
            Toast.makeText(getActivity(), "PASSING MESSAGE: " + adHocMessage.message, Toast.LENGTH_SHORT).show();

            addressToConnect = routeTable.get(adHocMessage.destinationAddress);

            //queue and connect
            mChatService.queueAdHocMessage(adHocMessage);
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(addressToConnect);
            mChatService.connect(device, false);

        }

        //don't know the destination, send route request
        else {

            Toast.makeText(getActivity(), "CREATING ROUTE_REQUEST", Toast.LENGTH_SHORT).show();

            //remember this message for later
            queuedAdHocMessage = adHocMessage;

            AdHocMessage routeRequest = createRouteRequest(adHocMessage.destinationAddress);

            propagateRouteRequest(routeRequest);
        }
    }

    public void propagateRouteRequest(AdHocMessage routeRequest) {

        //queue the route request and send when connected
        mChatService.queueAdHocMessage(routeRequest);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        getActivity().registerReceiver(sendRouteRequest, filter);

        int MY_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 1;
        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, MY_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION);

        // If we're already discovering, stop it
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }

        // Request discover from BluetoothAdapter
        mBluetoothAdapter.startDiscovery();
    }

    List<String> passingAddresses = null;
    private final BroadcastReceiver sendRouteRequest = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // When discovery finds a device
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                //connect to the device and send the message
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device.getAddress().equals(phone26)
                        || device.getAddress().equals(phone38)
                        || device.getAddress().equals(phone25)
                        || device.getAddress().equals(zohar)
                        || device.getAddress().equals(michael)
                        || device.getAddress().equals(nexusS1)
                        || device.getAddress().equals(nexusS2)) {

                    //dont send the route request back to where it came from
                    if (passingAddresses == null || !passingAddresses.contains(device.getAddress())) {
                        mChatService.connect(device, false);
                        mBluetoothAdapter.cancelDiscovery();
                        getActivity().unregisterReceiver(sendRouteRequest);
                    }
                }
            }
        }
    };

    private void promptForName(final String address, final String secretKeyString) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Enter A Name For This Device");

        // Set up the input
        final EditText input = new EditText(getContext());

        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                addressBook.put(address, new AddressBookItem(input.getText().toString(), secretKeyString));
                adHocConnectDevice(address);
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();

    }

    private void adHocConnectDevice(final String address) {

        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

        if (!addressBook.containsKey(address)) {
            mChatService.connect(device, false);

            String secretKeyString = null;
            try {
                SecretKey secretKey = KeyGenerator.getInstance("AES").generateKey();
                secretKeyString = Base64.encodeToString(secretKey.getEncoded(), Base64.DEFAULT);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            AdHocMessage message = new AdHocMessage();
            message.type = AdHocMessage.Type.PROMPT;
            message.sourceAddress = thisAddress;
            message.message = secretKeyString;
            mChatService.queueAdHocMessage(message);
            promptForName(address,secretKeyString);

        } else {

            if (conversations.containsKey(address)) {
                mConversationArrayAdapter = conversations.get(address);

            } else {
                mConversationArrayAdapter = new ArrayAdapter<String>(getActivity(), R.layout.message);
                conversations.put(address, mConversationArrayAdapter);
            }
            mConversationView.setAdapter(mConversationArrayAdapter);
            currentConversation = address;
            setStatus("Connected to " + addressBook.get(address).name);
        }

    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            FragmentActivity activity = getActivity();
            Toast.makeText(activity, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            activity.finish();

        }

    }


    @Override
    public void onStart() {
        super.onStart();
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
            // Otherwise, setup the chat session
        } else if (mChatService == null) {
            setupChat();
        }

        Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 900);
        startActivity(discoverableIntent);

        thisAddress = android.provider.Settings.Secure.getString(getActivity().getContentResolver(), "bluetooth_address");

        addressBook.put(phone25, new AddressBookItem("Phone 25","hoopla"));
        addressBook.put(phone26, new AddressBookItem("Phone 26","hoopla"));
        addressBook.put(phone38, new AddressBookItem("Phone 38","hoopla"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mChatService != null) {
            mChatService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                              Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bluetooth_chat, container, false);
    }

    @Override
    public void onViewCreated(View view,  Bundle savedInstanceState) {
        mConversationView = (ListView) view.findViewById(R.id.in);
        mOutEditText = (EditText) view.findViewById(R.id.edit_text_out);
        mSendButton = (Button) view.findViewById(R.id.button_send);
    }

    /**
     * Set up the UI and background operations for chat.
     */
    private void setupChat() {
        Log.d(TAG, "setupChat()");

        // Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = new ArrayAdapter<String>(getActivity(), R.layout.message);

        mConversationView.setAdapter(mConversationArrayAdapter);

        // Initialize the compose field with a listener for the return key
        mOutEditText.setOnEditorActionListener(mWriteListener);

        // Initialize the send button with a listener that for click events
        mSendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                View view = getView();
                if (null != view) {
                    TextView textView = (TextView) view.findViewById(R.id.edit_text_out);
                    String message = textView.getText().toString();

                    if (conversations.isEmpty()) {
                        Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
                        return;
                    }


                    mOutStringBuffer.setLength(0);
                    mOutEditText.setText(mOutStringBuffer);
                    mConversationArrayAdapter.add("Me: " + message);

                    AdHocMessage adHocMessage = createAdHocMessage(message, currentConversation);
                    sendAdHocMessage(adHocMessage);
                }
            }
        });

        // Initialize the BluetoothChatService to perform bluetooth connections
        mChatService = new BluetoothChatService(getActivity(), mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");

        setStatus("Choose a contact to start");
    }

    /**
     * Makes this device discoverable for 300 seconds (5 minutes).
     */
    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }

    private void stopDiscoverable() {
        if (mBluetoothAdapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 1);
            startActivity(discoverableIntent);
        }
    }

    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothChatService to write
            byte[] send = message.getBytes();
            mChatService.write(send);

            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer.setLength(0);
            mOutEditText.setText(mOutStringBuffer);
        }
    }

    /**
     * The action listener for the EditText widget, to listen for the return key
     */
    private TextView.OnEditorActionListener mWriteListener
            = new TextView.OnEditorActionListener() {
        public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
            // If the action is a key-up event on the return key, send the message
            if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
                String message = view.getText().toString();
                sendMessage(message);
            }
            return true;
        }
    };

    /**
     * Updates the status on the action bar.
     *
     * @param resId a string resource ID
     */
    private void setStatus(int resId) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(resId);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param subTitle status
     */
    private void setStatus(CharSequence subTitle) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(subTitle);
    }

    /**
     * The Handler that gets information back from the BluetoothChatService
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            FragmentActivity activity = getActivity();
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            //setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                            //mConversationArrayAdapter.clear();
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            //setStatus(R.string.title_connecting);
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            //setStatus(R.string.title_not_connected);
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    mConversationArrayAdapter.add("Me:  " + writeMessage);
                    break;
                case Constants.MESSAGE_READ:

                    //read the buffer
                    final byte[] readBuf = (byte[]) msg.obj;

                    //construct an AdHocMessage object from the valid bytes in the buffer
                    final AdHocMessage adHocMessage = mChatService.adHocFromBytes(readBuf);

                    if (adHocMessage.type == AdHocMessage.Type.PROMPT) {
                        mChatService.stop();
                        mChatService.start();
                        promptForName(adHocMessage.sourceAddress, adHocMessage.message);
                    } else {

                        //wait for connections to reset, then pass along the message
                        Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                                                public void run() {
                                                    sendAdHocMessage(adHocMessage);
                                                }
                                            }, 10
                        );

                    }
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != activity) {
                        //Toast.makeText(activity, "Connected to " + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != activity) {
                        Toast.makeText(activity, msg.getData().getString(Constants.TOAST), Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    adHocConnectDevice(data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS));
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(getActivity(), R.string.bt_not_enabled_leaving,
                            Toast.LENGTH_SHORT).show();
                    getActivity().finish();
                }
        }
    }

    /**
     * Establish connection with other device
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        String address = data.getExtras()
                .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mChatService.connect(device, secure);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.bluetooth_chat, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.insecure_connect_scan: {
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);


                return true;
            }
            case R.id.discoverable: {
                // Ensure this device is discoverable by others
                ensureDiscoverable();
                return true;
            }

            case R.id.stop_discoverable: {
                // Ensure this device is discoverable by others
                stopDiscoverable();
                return true;
            }
        }
        return false;
    }

}
