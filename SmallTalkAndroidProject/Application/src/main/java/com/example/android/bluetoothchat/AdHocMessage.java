package com.example.android.bluetoothchat;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Alex on 3/1/18.
 *
 * What to demo:
 * Connection between two nodes
 * Two nodes then an addition of a third
 * Three nodes with no knowledge of network
 * Far message sending
 * Network adjustments on the fly
 *
 * Node in the middle cant spy on the secret key
 */

public class AdHocMessage implements Serializable {

    //what type of message "packet" is this
    enum Type {
            ROUTE_REQUEST, //ROUTE REQUEST
            ROUTE_REPLY, //ROUTE REPLY
            ADHOC_MESSAGE, //MESSAGE
            PROMPT //REQUEST TO ADD NEW CONTACT
    }

    String sourceAddress = null; //MAC Address of the source
    String destinationAddress = null; //MAC Address of the destination
    Type type; //enum representing the type of the message

    String message = null; //the payload of the ad-hoc message

    Integer requestID = null; //used in route request packets
    Integer sourceSequenceNumber = null; //used in route request packets
    Integer destinationSequenceNumber = null; //used in route request/reply
    Integer hopCount = null; //how many nodes has this route request/reply seen

    List<String> passingAddresses = new ArrayList<String>(); //what MAC Addresses have seen this message
}
