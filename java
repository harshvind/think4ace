const express = require("express");
const fetch = require("node-fetch"); // For making HTTP requests in Node.js
const path = require("path");
require("dotenv").config(); // For loading environment variables from a .env file

const app = express();
const PORT = process.env.PORT || 3000; // Use port 3000 by default or an environment variable

// --- IBM Watson Machine Learning Configuration ---
const API_ENDPOINT = process.env.WML_API_ENDPOINT || "https://private.au-syd.ml.cloud.ibm.com/ml/v4/deployments/5c141254-0d86-453f-acbf-aa453253b3bf/predictions?version=2021-05-01";
const IBM_CLOUD_API_KEY = process.env.IBM_CLOUD_API_KEY; // Loaded from .env

if (!IBM_CLOUD_API_KEY) {
  console.error(
    'Error: IBM_CLOUD_API_KEY is not set. Please create a .env file with IBM_CLOUD_API_KEY="YOUR_API_KEY"'
  );
  process.exit(1); // Exit if API key is not set
}

// --- Middleware ---
app.use(express.json()); // To parse JSON bodies from incoming requests
app.use(express.static(path.join(__dirname))); // Serve static files (like index.html) from the current directory

// --- Function to get IAM Token (Backend-side) ---
async function getIamToken(apiKey) {
  const url = "https://iam.cloud.ibm.com/identity/token";
  const headers = { "Content-Type": "application/x-www-form-urlencoded" };
  const data = `apikey=${apiKey}&grant_type=urn:ibm:params:oauth:grant-type:apikey`;

  try {
    console.log("Backend: Attempting to fetch IAM token...");
    const response = await fetch(url, {
      method: "POST",
      headers: headers,
      body: data,
    });

    const result = await response.json();
    if (!response.ok) {
      console.error("Backend: IAM Token Error Response:", result);
      throw new Error(
        `Failed to get IAM token: ${response.status} - ${
          result.errorMessage || JSON.stringify(result)
        }`
      );
    }

    console.log("Backend: IAM Token fetched successfully.");
    if (result && result.access_token) {
      return result.access_token;
    } else {
      throw new Error(
        "IAM token response missing 'access_token'. Check API Key validity."
      );
    }
  } catch (error) {
    console.error("Backend: Error fetching IAM token:", error.message);
    throw error;
  }
}

// --- API Endpoint for Prediction ---
app.post("/predict", async (req, res) => {
  const { fields, values } = req.body; // Expecting fields and values from frontend

  // Basic validation
  if (
    !fields ||
    !Array.isArray(fields) ||
    !values ||
    !Array.isArray(values) ||
    values.length === 0
  ) {
    return res.status(400).json({ message: "Invalid input data provided." });
  }

  try {
    const token = await getIamToken(IBM_CLOUD_API_KEY);

    const wmlPayload = {
      input_data: [
        {
          fields: fields,
          values: values,
        },
      ],
    };

    console.log(
      "Backend: Sending WML Prediction Request:",
      JSON.stringify(wmlPayload, null, 2)
    );

    const wmlResponse = await fetch(API_ENDPOINT, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(wmlPayload),
    });

    const wmlResult = await wmlResponse.json();
    console.log(
      "Backend: WML Prediction Response:",
      JSON.stringify(wmlResult, null, 2)
    );

    if (!wmlResponse.ok) {
      console.error("Backend: WML API Error Body:", wmlResult);
      return res.status(wmlResponse.status).json({
        message: `WML API error: ${wmlResponse.status} - ${
          wmlResult.errors
            ? wmlResult.errors[0].message
            : JSON.stringify(wmlResult)
        }`,
      });
    }

    // --- Extract and send the predicted career ---
    let predictedCareer = "Unknown Career";
    if (
      wmlResult &&
      wmlResult.predictions &&
      wmlResult.predictions.length > 0
    ) {
      // This path is typical for AutoAI multiclass classification output
      // The predicted class is usually the first value in the first sub-array of 'values'
      if (
        wmlResult.predictions[0].values &&
        wmlResult.predictions[0].values[0] &&
        wmlResult.predictions[0].values[0].length > 0
      ) {
        predictedCareer = wmlResult.predictions[0].values[0][0];
      } else if (
        wmlResult.predictions[0].values &&
        wmlResult.predictions[0].values.length > 0
      ) {
        // For simpler models that might return just the value directly
        predictedCareer = wmlResult.predictions[0].values[0];
      }
    }

    console.log("Backend: Predicted Career:", predictedCareer);
    res.json({ predictedCareer: predictedCareer });
  } catch (error) {
    console.error("Backend: Error during prediction process:", error.message);
    res.status(500).json({
      message: "Internal server error during prediction.",
      error: error.message,
    });
  }
});

// --- Start the Server ---
app.listen(PORT, () => {
  console.log(`Server running on http://localhost:${PORT}`);
  console.log(`Open your browser to http://localhost:${PORT}/index.html`);
});
