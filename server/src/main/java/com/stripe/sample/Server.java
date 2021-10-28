package com.stripe.sample;

import java.nio.file.Paths;
import java.nio.charset.Charset;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static spark.Spark.get;
import static spark.Spark.post;
import static spark.Spark.port;
import static spark.Spark.staticFiles;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.checkout.Session;
import com.stripe.model.Price;
import com.stripe.model.StripeObject;
import com.stripe.exception.*;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem;
import com.stripe.param.checkout.SessionCreateParams.PaymentMethodType;

import io.github.cdimascio.dotenv.Dotenv;

public class Server {
    private static Gson gson = new Gson();

    public static void main(String[] args) {
        port(4242);

        //To manage 환경변수 easily
        Dotenv dotenv = Dotenv.load();


        //To checking PRICE_ID is set or not
        checkEnv();

        Stripe.apiKey = dotenv.get("STRIPE_SECRET_KEY");
        Stripe.setAppInfo(
            "stripe-samples/checkout-one-time-payments",
            "0.0.1",
            "https://github.com/stripe-samples/checkout-one-time-payments"
        );


        staticFiles.externalLocation(Paths.get(Paths.get("").toAbsolutePath().toString(), dotenv.get("STATIC_DIR")).normalize().toString());

        get("/config", (request, response) -> {
            System.out.println("=============START /CONFIG==============");

            response.type("application/json");
            Price price = Price.retrieve(dotenv.get("PRICE"));
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("publicKey", dotenv.get("STRIPE_PUBLISHABLE_KEY"));
            responseData.put("unitAmount", price.getUnitAmount());
            responseData.put("currency", price.getCurrency());
            System.out.println("=============FINISH /CONFIG==============");

            return gson.toJson(responseData);
        });



        get("/checkout-session", (request, response) -> {
            System.out.println("===========START /CHECKOUT-SESSION============");

            response.type("application/json");
            System.out.println("/checkout-session request::"+request.body());
            System.out.println("----------------------------------------------------------------------");

            String sessionId = request.queryParams("sessionId");
            Session session = Session.retrieve(sessionId);

            System.out.println("session::"+gson.toJson(session));
            System.out.println("----------------------------------------------------------------------");
            System.out.println("===========FINISH /CHECKOUT-SESSION============");

            return gson.toJson(session);
        });

        post("/create-checkout-session", (request, response) -> {
            System.out.println("===========START /create-checkout-session============");

            String domainUrl = dotenv.get("DOMAIN");
            Long quantity = Long.parseLong(request.queryParams("quantity"));
            String price = dotenv.get("PRICE");

            String[] pmTypes = dotenv.get("PAYMENT_METHOD_TYPES", "card").split(",", 0);

            List<PaymentMethodType> paymentMethodTypes = Stream
              .of(pmTypes)
              .map(String::toUpperCase)
              .map(PaymentMethodType::valueOf)
              .collect(Collectors.toList());
            //paymentMethidTypes = CARD


            // Create new Checkout Session for the order
            // set as a query param
            SessionCreateParams.Builder builder = new SessionCreateParams.Builder()
                    .setSuccessUrl(domainUrl + "/success.html?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(domainUrl + "/canceled.html")
                    .addAllPaymentMethodType(paymentMethodTypes) //CARD
                    .setMode(SessionCreateParams.Mode.PAYMENT);

            // Promotion code
            LineItem item = new LineItem.Builder().setQuantity(quantity).setPrice(price).build();
            builder.addLineItem(item);

            SessionCreateParams createParams = builder.build();

            Session session = Session.create(createParams);

            System.out.println("session::"+ session);
            System.out.println("----------------------------------------------------------------------");

            response.redirect(session.getUrl(), 303);
            System.out.println("===========FINISH /create-checkout-session============");
            return "";
        });


        post("/webhook", (request, response) -> {
            System.out.println("===========START /webhook============");

            System.out.println("webhook request::"+ request.body());
            System.out.println("webhook response::"+response.body());
            String payload = request.body();
            String sigHeader = request.headers("Stripe-Signature");
            String endpointSecret = dotenv.get("STRIPE_WEBHOOK_SECRET");
            Event event = null;

            try {
                event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
                System.out.println("event::"+event);
                System.out.println("----------------------------------------------------------------------");
            } catch (SignatureVerificationException  e) {
                // Invalid payload
                System.out.println("⚠️  Webhook error while parsing basic request.");
                response.status(400);
                return "";
            }

            
            // Deserialize the nested object inside the event API 버전 체크하기 위해서
            EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
            StripeObject stripeObject = null;

            if (dataObjectDeserializer.getObject().isPresent()) {
                stripeObject = dataObjectDeserializer.getObject().get();
                // System.out.println("stripeObject::"+stripeObject);
                // System.out.println("----------------------------------------------------------------------");
            } else {
                // Deserialization failed, probably due to an API version mismatch.
                // Refer to the Javadoc documentation on `EventDataObjectDeserializer` for
                // instructions on how to handle this case, or return an error here.
            }

            //버전이 맞아야 아래 Handle event를 실행할 수 있음.



            // Handle the event
            switch (event.getType()) {
                case "payment_intent.succeeded":
                    PaymentIntent paymentIntent = (PaymentIntent) stripeObject;
                    System.out.println("Payment for " + paymentIntent.getAmount() + " succeeded.");
                    System.out.println("----------------------------------------------------------------------");
                    break;
                default:
                    System.out.println("Unhandled event type: " + event.getType());
                    System.out.println("************************************************************************************");
                break;
            }
            response.status(200);
            return "";
        });
    }

    public static void checkEnv() {
        Dotenv dotenv = Dotenv.load();
        String price = dotenv.get("PRICE");
        if(price == "price_12345" || price == "" || price == null) {
          System.out.println("You must set a Price ID in the .env file. Please see the README.");
          System.exit(0);
        }
    }
}
