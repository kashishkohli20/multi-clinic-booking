import os
import requests
from typing import Optional, List, Dict
from fastapi import FastAPI, HTTPException
from langchain_core.messages import HumanMessage, AIMessage
from pydantic import BaseModel, Field
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_openai import ChatOpenAI
from dotenv import load_dotenv
import redis
import json

# Load environment variables from the .env file immediately on startup
load_dotenv()

app = FastAPI(title="Booking AI Assistant Service")

# 🔌 Initialize the Redis Client connection pool
# For local dev, it connects to localhost:6379
redis_client = redis.Redis(host='localhost', port=6379, decode_responses=True)

# Incoming payload layout from your frontend chat UI
class ChatRequest(BaseModel):
    session_id: str = Field(..., description = "Unique tracker string for this user session")
    message: str


# Define the exact layout the LLM must extract from human speech
class ExtractedBookingIntent(BaseModel):
    provider_name: Optional[str] = Field(None, description="The name of the doctor or specialist, e.g., 'Dr. Smith'")
    day_of_week: Optional[str] = Field(None, description="The day of the week requested, e.g., 'Monday'")
    time_slot: Optional[str] = Field(None, description="A precise clock time or well-defined business period, e.g., '10:00 AM', '2:30 PM', 'morning', 'afternoon'. "
                    "CRITICAL: If the user says vague phrases like 'anytime', 'whenever', 'asap', or 'any hour', you MUST "
                    "return null for this field because a specific appointment cannot be scheduled yet."
   )


# Spring Boot Configuration
# SPRING_BOOT_URL = "http://localhost:8080/api/v1"


@app.post("/api/v1/chat")
def process_chat_message(request: ChatRequest):
    # 1. Fetch or initialize the specific user's chat history array
    redis_key = f"booking session:{request.session_id}"

    cached_data = redis_client.get(redis_key)
    if cached_data:
        accumulated = json.loads(cached_data)
    else:
        # Initial empty blueprint state map structure
        accumulated = {
            "provider_name": None,
            "day_of_week": None,
            "time_slot": None
        }

    if not os.environ.get("OPENAI_API_KEY"):
        return handle_mock_fallback(request.message)

    try:
        llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
        structured_llm = llm.with_structured_output(ExtractedBookingIntent)

        # 2. Refined Contextual System Prompt
        system_prompt = (
            "You are a backend data extraction assistant for an appointment booking engine.\n"
            "Analyze the entire conversation log and populate the requested JSON schema rules.\n\n"
            "CRITICAL:\n"
            "If an item (like the doctor's name) was established in an earlier message turn, "
            "do not clear it. Retain any previously extracted values unless the user explicitly "
            "corrects or changes them in their latest response. Extract values globally from the history log."
        )

        prompt = ChatPromptTemplate.from_messages([
            ("system", system_prompt),
            ("human", "{user_input}")  # Injects all history up to the latest input
        ])

        extractor_chain = prompt | structured_llm

        # 3. Fire the entire message timeline package over to OpenAI
        extracted_data: ExtractedBookingIntent = extractor_chain.invoke({
            "user_input": request.message
        })

        # 2. Merge values natively into our local tracked variable footprint
        if extracted_data.provider_name:
            accumulated["provider_name"] = extracted_data.provider_name
        if extracted_data.day_of_week:
            accumulated["day_of_week"] = extracted_data.day_of_week
        if extracted_data.time_slot:
            accumulated["time_slot"] = extracted_data.time_slot

        # 4. Multi-turn check boundaries
        if not accumulated["provider_name"] or not accumulated["day_of_week"] or not accumulated["time_slot"]:
            reply_msg = (
                f"I am building your request. Currently I have: "
                f"Doctor: {accumulated['provider_name'] or 'Missing'}, "
                f"Day: {accumulated['day_of_week'] or 'Missing'}, "
                f"Time: {accumulated['time_slot'] or 'Missing'}. "
                f"Could you give me the remaining details?"
            )

            redis_client.setex(redis_key, 600, json.dumps(accumulated))

            return {
                "status": "NEED_CLARIFICATION",
                "reply": reply_msg,
                "parsed_data": accumulated
            }

        target_doctor = accumulated["provider_name"]

        redis_client.delete(redis_key)

        # =========================================================================
        # SPRING BOOT TRANSACT BRIDGE (Remains identical)
        # =========================================================================
        spring_boot_search_url = f"http://localhost:8080/api/v1/slots/search"
        response = requests.get(spring_boot_search_url, params={"doctorName": target_doctor})

        if response.status_code == 404 or not response.json():
            return {"status": "NOT_AVAILABLE",
                    "reply": f"I tracked your parameters, but {extracted_data.provider_name} has no slots."}

        available_slots = response.json()
        selected_slot = available_slots[0]
        slot_id = selected_slot["id"]

        hold_payload = {"slotId": slot_id, "patientId": "0cabf7bc-b5e4-4399-91f2-b6e0bd9dbe90"}
        hold_response = requests.post(f"http://localhost:8080/api/v1/slots/hold", json=hold_payload)
        hold_information = hold_response.json()

        return {
            "status": "READY_TO_CONFIRM",
            "reply": f"Excellent! I've placed a temporary hold on a slot with {extracted_data.provider_name} for you. Hold ID: {hold_information['id']}.",
            "hold_id": hold_information["id"]
        }

    except Exception as e:
        # Prevent crash footprint leakage, pop history on code execution exception failure
        raise HTTPException(status_code=500, detail=str(e))


# @app.post("/api/v1/chat")
# def process_chat_message(request: ChatRequest):
#     # 1. Fetch or initialize the specific user's chat history array
#     if request.session_id not in chat_history_db:
#         chat_history_db[request.session_id] = []
#
#     history = chat_history_db[request.session_id]
#     print(history)
#     # 1. Initialize the LLM (Requires OPENAI_API_KEY environment variable setup)
#     # You can swap this to ChatAnthropic or local Ollama easily down the road
#     # if not os.environ.get("OPENAI_API_KEY"):
#     if not os.environ.get("EURI_KEY"):
#         # For learning purposes, return dummy data if you don't have an API key yet
#         return handle_mock_fallback(request.message)
#     try:
#         llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
#
#         # 2. Force the LLM to structure its output directly to our Pydantic model class
#         structured_llm = llm.with_structured_output(ExtractedBookingIntent)
#
#         # # 3. Create the prompt context guiding the AI's internal parser logic
#         # system_prompt = (
#         #     "You are an expert backend API data extraction tool. Analyze the user's input "
#         #     "and accurately extract booking attributes. If a value is missing, leave it as null."
#         # )
#         # 2. Advanced Prompt: Include a placeholder to inject conversation logs directly to the AI's window
#         # ◄ UPDATED SYSTEM PROMPT FOR DATA RETENTION ►
#         system_prompt = (
#             "You are an expert conversational data extraction assistant for an appointment booking engine.\n\n"
#             "CRITICAL RULE:\n"
#             "Review the provided 'chat_history' carefully before processing the new human message. "
#             "If an attribute (like the doctor's name, day, or time) was already specified by the human "
#             "or acknowledged by the AI in previous turns, you MUST carry that value forward and retain it "
#             "in your output, UNLESS the human explicitly changes it in their latest message.\n\n"
#             "Do not allow previously extracted fields to revert to null just because they aren't mentioned "
#             "in the current sentence. Merge past context with the new input."
#         )
#
#         prompt = ChatPromptTemplate.from_messages([
#             ("system", system_prompt),
#             MessagesPlaceholder(variable_name = "chat_history"), # ◄ Injects chat timeline here!
#             ("human", "{user_input}")
#         ])
#         print(prompt, "my prompt")
#
#         # 4. Invoke the AI chain
#         extractor_chain = prompt | structured_llm
#         print(extractor_chain, "my extractor chain")
#         extracted_data: ExtractedBookingIntent = extractor_chain.invoke({
#             "user_input": request.message,
#             "chat_history": history
#         })
#         print(extracted_data, "my extracted data")
#
#         # 5. Business Routing Logic based on parsing completion status
#         if not extracted_data.provider_name or not extracted_data.day_of_week or not extracted_data.time_slot:
#             reply_msg = f"I am building your request. Currently I have: Doctor: {extracted_data.provider_name or 'Missing'}, Day: {extracted_data.day_of_week or 'Missing'}, Time: {extracted_data.time_slot or 'Missing'}. Could you give me the remaining details?"
#
#             history.append(HumanMessage(content=request.message))
#             history.append((AIMessage(content=reply_msg)))
#
#             return {
#                 "status": "NEED_CLARIFICATION",
#                 # "reply": f"I caught that you want to see {extracted_data.provider_name or 'someone'}, but could you please specify what day and time works best?",
#                 "reply": reply_msg,
#                 "parsed_data": extracted_data.dict()
#             }
#         # 1. Query Spring Boot to find available slots for this doctor
#         spring_boot_search_url = f"http://localhost:8080/api/v1/slots/search"
#         response = requests.get(spring_boot_search_url, params={"doctorName": extracted_data.provider_name})
#         print(response.text, "myresponse")
#
#         # If Spring Boot returns a 404 (Doctor Not Found), pass that back cleanly
#         if response.status_code == 404 or not response.json():
#             # return {
#             #     "status": "DOCTOR_NOT_FOUND",
#             #     "reply": f"I couldn't find a specialist named '{extracted_data.provider_name}' in our core system. Could you check the spelling?",
#             #     "parsed_data": extracted_data.dict()
#             # }
#             reply_msg = f"I tracked your parameters, but {extracted_data.provider_name} has no available slots."
#             return {"status": "NOT_AVAILABLE", "reply": reply_msg}
#
#         available_slots = response.json()
#
#         # 2. Check if any slots came back
#         # if not available_slots:
#         #     return {
#         #         "status": "NO_SLOTS_AVAILABLE",
#         #         "reply": f"I found {extracted_data.provider_name}, but they don't have any open appointments right now.",
#         #         "parsed_data": extracted_data.dict()
#         #     }
#
#         # 3. For now, grab the first available Slot UUID returned by Spring Boot
#         selected_slot = available_slots[0]
#         slot_id = selected_slot["id"]
#         slot_start_time = selected_slot["startTime"]
#         slot_end_time = selected_slot["endTime"]
#
#         # HOLD the slot for the time being
#         spring_boot_search_url = f"http://localhost:8080/api/v1/slots/hold"
#         hold_payload = {"slotId": slot_id, "patientId": "0cabf7bc-b5e4-4399-91f2-b6e0bd9dbe90"}
#         response = requests.post(spring_boot_search_url, json = hold_payload)
#         print(response.text, "myresponse")
#
#         # if response.status_code == 404:
#         #     return {
#         #         "status": "SLOT_NOT_FOUND",
#         #         "reply": f"Looks like this slot is not available",
#         #         "parsed_data": extracted_data.dict()
#         #     }
#
#         hold_information = response.json()
#         chat_history_db[request.session_id] = []
#
#         # 2. Check if any slots came back
#         # if not hold_information:
#         #     return {
#         #         "status": "NO_SLOTS_AVAILABLE",
#         #         "reply": f"Slot is not open",
#         #         "parsed_data": extracted_data.dict()
#         #     }
#
#         hold_slot = hold_information
#         hold_id = hold_slot["id"]
#         hold_expiry_time = hold_slot["expiresAt"]
#         hold_status = hold_slot["status"]
#
#         return {
#             "status": "READY_TO_CONFIRM",
#             "reply": f"Excellent! I've placed a temporary hold on a slot with {extracted_data.provider_name} for you. Hold ID: {hold_id}.",
#             "hold_id": hold_id
#         }
#
#         # return {
#         #     "status": "READY_TO_CONFIRM",
#         #     "reply": f"Great news! I found an open slot with {extracted_data.provider_name}. Slot ID is {slot_id}. Slot start time is {slot_start_time} and end time is {slot_end_time}. We have put a hold {hold_id} on your booking {hold_status} and you need to complete booking by {hold_expiry_time}",
#         #     "parsed_data": extracted_data.dict(),
#         #     "target_slot_id": slot_id
#         # }
#
#         # 6. If data is complete, simulate or fire the trigger down to Spring Boot
#         # (We will expand the Spring Boot bridge API connection endpoint logic next!)
#         # return {
#         #     "status": "READY_TO_BOOK",
#         #     "reply": f"Perfect! I am looking for a slot with {extracted_data.provider_name} on {extracted_data.day_of_week} at {extracted_data.time_slot}.",
#         #     "parsed_data": extracted_data.dict()
#         # }
#     except Exception as e:
#         print(e)
#         # If OpenAI returns an API error (billing, quota, invalid key), catch it gracefully here
#         raise HTTPException(status_code=500, detail=f"OpenAI API Error: {str(e)}")


def handle_mock_fallback(message: str):
    """Temporary offline fallback mechanism so you can build out code connectivity without paying for an API key upfront."""
    return {
        "status": "MOCK_MODE_ACTIVE",
        "reply": f"Mock parser received: '{message}'. Set OPENAI_API_KEY to active live LLM logic processing.",
        "parsed_data": {"provider_name": "Dr. Smith", "day_of_week": "Monday", "time_slot": "10:00 AM"}
    }
