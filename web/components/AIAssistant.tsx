/**
 * AIAssistant.tsx - Intelligent Message Analysis Component
 *
 * This component provides an AI-powered assistant that analyzes SMS/MMS messages
 * to extract financial insights, track packages, identify OTP codes, and more.
 *
 * KEY FEATURES:
 * - Transaction parsing: Extracts spending data from bank SMS notifications
 * - Bill tracking: Identifies upcoming bills and due dates
 * - Balance monitoring: Detects account balance information
 * - Subscription detection: Finds recurring charges and calculates monthly costs
 * - Package tracking: Identifies delivery status from shipping notifications
 * - OTP extraction: Finds verification codes from recent messages
 *
 * ARCHITECTURE:
 * - All analysis is performed client-side using pattern matching (no external AI API)
 * - Uses React memoization (useMemo) to optimize expensive parsing operations
 * - Supports multiple currencies (USD, INR) with automatic detection
 * - Maintains conversation history for contextual follow-up queries
 *
 * DATA FLOW:
 * 1. Parent component passes array of Message objects
 * 2. Component parses messages into structured data (transactions, bills, etc.)
 * 3. User queries trigger the generateResponse function
 * 4. Response is rendered with suggested follow-up queries
 *
 * @module components/AIAssistant
 * @requires react
 * @requires lucide-react
 */

'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import { Send, Brain, X, TrendingUp, Calendar, Package, CreditCard, Key, ListTodo, Copy, Check, RefreshCw, ArrowUpDown, Repeat, Plus } from 'lucide-react'

/**
 * Represents an SMS/MMS message from the user's phone
 * @property id - Unique identifier for the message
 * @property address - Phone number or sender address
 * @property body - The text content of the message
 * @property date - Unix timestamp of when the message was received
 * @property type - Message type (1=received, 2=sent)
 * @property contactName - Optional display name from contacts
 */
interface Message {
  id: string | number
  address: string
  body: string
  date: number
  type: number
  contactName?: string
}

/**
 * Props for the AIAssistant component
 * @property messages - Array of SMS/MMS messages to analyze
 * @property onClose - Optional callback when the assistant modal is closed
 */
interface AIAssistantProps {
  messages: Message[]
  onClose?: () => void
}

/**
 * Represents a parsed financial transaction extracted from an SMS
 * Used for spending analysis and merchant tracking
 */
interface ParsedTransaction {
  amount: number
  currency: string
  merchant: string | null
  date: number
  message: string
}

/**
 * Represents a bill or payment reminder extracted from messages
 * Includes due date parsing and overdue status detection
 */
interface BillReminder {
  billType: string
  dueDate: Date | null
  amount: number | null
  currency: string
  merchant: string
  message: string
  date: number
  isOverdue: boolean
}

/**
 * Represents account balance information from bank SMS notifications
 */
interface BalanceInfo {
  accountType: string
  balance: number | null
  currency: string
  institution: string | null
  message: string
  date: number
}

/**
 * Represents a detected recurring expense or subscription
 * Includes frequency detection and monthly cost estimation
 */
interface RecurringExpense {
  merchant: string
  amount: number
  currency: string
  frequency: 'monthly' | 'weekly' | 'yearly'
  lastCharge: number
  occurrences: number
}

/**
 * Aggregated financial summary for the smart digest feature
 * Provides month-over-month comparisons and key metrics
 */
interface SmartDigest {
  totalSpentThisMonth: number
  totalSpentLastMonth: number
  spendingChange: number
  transactionCount: number
  upcomingBills: number
  recentPackages: number
  subscriptionTotal: number
  topMerchant: string | null
  currency: string
}

/**
 * Quick action buttons displayed on the assistant home screen
 * Each action maps to a pre-defined query that users can click
 */
const QUICK_ACTIONS = [
  { icon: TrendingUp, title: 'Spending', query: 'How much did I spend this month?', color: 'blue' },
  { icon: Calendar, title: 'Bills', query: 'Show my upcoming bills', color: 'orange' },
  { icon: Package, title: 'Packages', query: 'Track my packages', color: 'green' },
  { icon: CreditCard, title: 'Balance', query: 'What is my account balance?', color: 'purple' },
  { icon: Repeat, title: 'Subscriptions', query: 'Show my subscriptions', color: 'indigo' },
  { icon: ArrowUpDown, title: 'Compare', query: 'Compare spending vs last month', color: 'cyan' },
  { icon: Key, title: 'OTPs', query: 'Show my OTP codes', color: 'red' },
  { icon: ListTodo, title: 'Transactions', query: 'List my transactions', color: 'teal' },
]

/**
 * Mapping of merchant names to their common aliases and abbreviations
 * Used for fuzzy matching when extracting merchant names from SMS text
 * Key: canonical merchant name, Value: array of aliases to match
 */
const MERCHANT_ALIASES: Record<string, string[]> = {
  'amazon': ['amazon', 'amzn', 'amazn', 'amz'],
  'flipkart': ['flipkart', 'fkrt', 'flip'],
  'walmart': ['walmart', 'wmt'],
  'uber': ['uber'],
  'swiggy': ['swiggy'],
  'zomato': ['zomato'],
  'google': ['google', 'goog'],
  'apple': ['apple', 'itunes'],
  'netflix': ['netflix'],
  'spotify': ['spotify'],
  'doordash': ['doordash'],
  'starbucks': ['starbucks', 'sbux'],
  'myntra': ['myntra'],
  'bigbasket': ['bigbasket', 'bbsk'],
  'paytm': ['paytm'],
  'phonepe': ['phonepe'],
  'gpay': ['gpay', 'googlepay', 'google pay'],
  'target': ['target'],
  'costco': ['costco'],
  'bestbuy': ['best buy', 'bestbuy'],
  'xfinity': ['xfinity', 'comcast'],
  'att': ['at&t', 'att'],
  'verizon': ['verizon', 'vzw'],
  'tmobile': ['t-mobile', 'tmobile'],
  'icici': ['icici', 'icicbank', 'icicibank'],
  'hdfc': ['hdfc', 'hdfcbank'],
  'sbi': ['sbi', 'state bank', 'statebank'],
  'axis': ['axis', 'axisbank'],
  'kotak': ['kotak', 'kotakbank'],
}

/**
 * Maps merchants/banks to their typical currency
 * Used to correctly identify transaction currency based on merchant context
 */
const MERCHANT_CURRENCY_MAP: Record<string, string> = {
  // Indian banks and services
  'icici': 'INR',
  'hdfc': 'INR',
  'sbi': 'INR',
  'axis': 'INR',
  'kotak': 'INR',
  'paytm': 'INR',
  'phonepe': 'INR',
  'gpay': 'INR',
  'googlepay': 'INR',
  'google pay': 'INR',
  'swiggy': 'INR',
  'zomato': 'INR',
  'flipkart': 'INR',
  'myntra': 'INR',
  'bigbasket': 'INR',

  // US services and banks
  'xfinity': 'USD',
  'comcast': 'USD',
  'at&t': 'USD',
  'att': 'USD',
  'verizon': 'USD',
  'tmobile': 'USD',
  't-mobile': 'USD',
  'wells fargo': 'USD',
  'wellsfargo': 'USD',
  'chase': 'USD',
  'bank of america': 'USD',
  'bofa': 'USD',
  'boa': 'USD',
  'citi': 'USD',
  'citibank': 'USD',
  'amex': 'USD',
  'american express': 'USD',
  'discover': 'USD',
  'capital one': 'USD',
  'capitalone': 'USD',

  // International services (default USD)
  'amazon': 'USD',
  'uber': 'USD',
  'netflix': 'USD',
  'spotify': 'USD',
  'apple': 'USD',
  'google': 'USD',
  'walmart': 'USD',
  'doordash': 'USD',
  'starbucks': 'USD',
  'target': 'USD',
  'costco': 'USD',
  'bestbuy': 'USD',
}

/**
 * Keywords that MUST be present in a message to be classified as a debit transaction
 * These help filter out non-spending messages that might contain amounts
 */
const DEBIT_KEYWORDS = [
  'debited', 'spent', 'paid', 'charged', 'purchase', 'payment',
  'debit', 'deducted', 'txn', 'transaction', 'pos', 'withdrawn'
]

/**
 * Keywords that indicate a message is a credit (NOT a spending)
 * Messages containing these are excluded from spending calculations
 */
const CREDIT_KEYWORDS = [
  'credited', 'received', 'refund', 'reversal', 'cashback',
  'credit', 'deposit', 'deposited', 'added', 'bonus', 'reward'
]

/**
 * AIAssistant - Main component for the intelligent message analysis assistant
 *
 * Provides a chat-like interface for users to query their SMS messages.
 * All analysis is performed locally using pattern matching and heuristics.
 *
 * @param messages - Array of SMS/MMS messages to analyze
 * @param onClose - Optional callback when the modal is closed
 *
 * @example
 * ```tsx
 * <AIAssistant
 *   messages={userMessages}
 *   onClose={() => setShowAssistant(false)}
 * />
 * ```
 */
export default function AIAssistant({ messages, onClose }: AIAssistantProps) {
  // User's current input query
  const [query, setQuery] = useState('')

  // Conversation history for contextual chat experience
  const [conversation, setConversation] = useState<Array<{ role: 'user' | 'assistant', content: string }>>([])

  // Loading state during response generation
  const [isLoading, setIsLoading] = useState(false)

  // Track which response was copied (for copy button feedback)
  const [copiedIndex, setCopiedIndex] = useState<number | null>(null)

  // Whether to show the smart digest card on home screen
  const [showDigest, setShowDigest] = useState(true)

  // Track timeout IDs for cleanup on unmount
  const timeoutsRef = useRef<ReturnType<typeof setTimeout>[]>([])

  /**
   * Cleanup effect - clears all pending timeouts when component unmounts
   * Prevents memory leaks and state updates on unmounted components
   */
  useEffect(() => {
    return () => {
      timeoutsRef.current.forEach((id) => clearTimeout(id))
      timeoutsRef.current = []
    }
  }, [])

  /**
   * Extracts a known merchant name from the user's query
   * Used to filter transactions by merchant when user asks "Amazon spending"
   *
   * @param query - The user's input query string
   * @returns The canonical merchant name if found, null otherwise
   */
  const extractMerchantFromQuery = (query: string): string | null => {
    const lowerQuery = query.toLowerCase()
    for (const [merchant, aliases] of Object.entries(MERCHANT_ALIASES)) {
      if (aliases.some(alias => lowerQuery.includes(alias))) {
        return merchant
      }
    }
    return null
  }

  /**
   * Extracts merchant/institution name from an SMS message body
   * Uses multiple pattern matching strategies:
   * 1. Known merchant aliases lookup
   * 2. Bank name patterns (e.g., "HDFC Bank", "SBI Alert")
   * 3. SMS sender code patterns (e.g., "VK-HDFCBK")
   * 4. General merchant extraction patterns
   *
   * @param body - The raw SMS message body text
   * @returns Extracted merchant name or null if not found
   */
  const extractMerchantFromMessage = (body: string): string | null => {
    const bodyLower = body.toLowerCase()

    // Check known merchants first
    for (const [merchant, aliases] of Object.entries(MERCHANT_ALIASES)) {
      if (aliases.some(alias => bodyLower.includes(alias))) {
        return merchant.charAt(0).toUpperCase() + merchant.slice(1)
      }
    }

    // Try to extract bank name from patterns like "HDFC Bank", "SBI Alert"
    const bankPatterns = [
      /([A-Z]{2,10})\s+Bank/,           // "HDFC Bank", "ICICI Bank"
      /([A-Z]{2,10})\s+Alert/,          // "SBI Alert"
      /([A-Z][A-Za-z]+)\s+Bank/,        // "Axis Bank", "Kotak Bank"
      /([A-Z][A-Za-z]+)\s+Credit/,      // "Amex Credit"
      /Dear\s+([A-Z][A-Za-z]+)\s+Card/i, // "Dear HDFC Card"
      /from\s+([A-Z][A-Za-z]+)\s+A\/c/i, // "from HDFC A/c"
      /([A-Z]{2,10})Bank/,              // "HDFCBank" (no space)
    ]

    for (const pattern of bankPatterns) {
      const match = body.match(pattern)
      if (match && match[1]) {
        const extracted = match[1].trim()
        const skipWords = ['your', 'the', 'dear', 'from', 'with', 'card']
        if (!skipWords.includes(extracted.toLowerCase()) && extracted.length >= 2) {
          return extracted
        }
      }
    }

    // Try to extract from sender patterns (VK-HDFCBK, AD-ICICIB, etc.)
    const senderPattern = /(?:VK|AD|VM|BZ|HP|TD|DM|AX)-([A-Z]{3,8})/
    const senderMatch = body.match(senderPattern)
    if (senderMatch && senderMatch[1]) {
      const code = senderMatch[1]
      // Map common bank codes
      const bankCodes: Record<string, string> = {
        'HDFCBK': 'HDFC', 'ICICIB': 'ICICI', 'SBIINB': 'SBI', 'AXISBK': 'Axis',
        'KOTAKB': 'Kotak', 'PNBBK': 'PNB', 'BOBBK': 'BOB', 'YESBK': 'Yes Bank',
        'IABORB': 'IOB', 'CANBNK': 'Canara', 'UNIONB': 'Union', 'INDUSB': 'IndusInd'
      }
      return bankCodes[code] || code
    }

    // Try to extract from general patterns
    const merchantPatterns = [
      /(?:at|to|from)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})(?:\s+(?:on|for|ref|card)|$)/i,
      /(?:txn|transaction|purchase)\s+(?:at|on|to)\s+([A-Za-z][A-Za-z0-9\s&'./-]{2,25})/i,
    ]

    for (const pattern of merchantPatterns) {
      const match = body.match(pattern)
      if (match && match[1]) {
        const extracted = match[1].trim()
        const skipWords = ['your', 'the', 'a', 'an', 'card', 'account', 'bank', 'ending']
        if (extracted.length >= 2 && extracted.length <= 25 &&
            !skipWords.some(w => extracted.toLowerCase().startsWith(w))) {
          return extracted.charAt(0).toUpperCase() + extracted.slice(1).toLowerCase()
        }
      }
    }

    return null
  }

  /**
   * Detects the currency for a transaction based on merchant, message content, and sender
   *
   * Detection Strategy (in order of priority):
   * 1. Check if merchant is in MERCHANT_CURRENCY_MAP
   * 2. Look for explicit currency symbols/codes in message ($, ₹, INR, USD)
   * 3. Check sender phone number pattern (Indian numbers start with +91 or are 10 digits)
   * 4. Default to USD for unrecognized patterns
   *
   * @param messageBody - The SMS message text
   * @param merchant - Extracted merchant name (if any)
   * @param senderAddress - The phone number/short code of the sender
   * @returns Currency code (INR, USD, etc.)
   */
  const detectCurrency = (messageBody: string, merchant: string | null, senderAddress: string): string => {
    const bodyLower = messageBody.toLowerCase()

    // 1. Check merchant-specific currency mapping
    if (merchant) {
      const merchantLower = merchant.toLowerCase()
      if (MERCHANT_CURRENCY_MAP[merchantLower]) {
        return MERCHANT_CURRENCY_MAP[merchantLower]
      }

      // Also check if any key in the map is contained in merchant name or body
      for (const [key, currency] of Object.entries(MERCHANT_CURRENCY_MAP)) {
        if (merchantLower.includes(key) || bodyLower.includes(key)) {
          return currency
        }
      }
    }

    // 2. Check for explicit currency indicators in message
    if (bodyLower.includes('₹') || bodyLower.includes('inr') ||
        bodyLower.includes('rs.') || bodyLower.includes('rs ')) {
      return 'INR'
    }
    if (bodyLower.includes('$') || bodyLower.includes('usd')) {
      return 'USD'
    }
    if (bodyLower.includes('€') || bodyLower.includes('eur')) {
      return 'EUR'
    }
    if (bodyLower.includes('£') || bodyLower.includes('gbp')) {
      return 'GBP'
    }
    if (bodyLower.includes('¥') || bodyLower.includes('jpy')) {
      return 'JPY'
    }

    // 3. Check sender pattern for Indian numbers
    const cleanedSender = senderAddress.replace(/[^0-9+]/g, '')
    if (cleanedSender.startsWith('+91')) {
      return 'INR'
    }
    if (cleanedSender.startsWith('91') && cleanedSender.length > 10) {
      return 'INR'
    }
    if (cleanedSender.length === 10 && !cleanedSender.startsWith('1')) {
      return 'INR' // Likely Indian
    }
    if (cleanedSender.startsWith('+1') || cleanedSender.startsWith('1')) {
      return 'USD'
    }

    // 4. Check for Indian bank keywords
    if (bodyLower.includes('bank') && (bodyLower.includes('india') ||
        bodyLower.includes('mumbai') || bodyLower.includes('delhi'))) {
      return 'INR'
    }

    // Default to USD for short codes and unknown patterns
    return 'USD'
  }

  /**
   * Parses all messages to extract financial transactions
   * Memoized for performance - only recalculates when messages change
   *
   * FILTERING RULES:
   * - Must contain at least one DEBIT_KEYWORD
   * - Must NOT contain any CREDIT_KEYWORD (excludes refunds, deposits)
   * - Amount must be positive and less than 10,000,000 (filters reference numbers)
   *
   * @returns Array of ParsedTransaction objects sorted by date (newest first)
   */
  const parseTransactions = useMemo((): ParsedTransaction[] => {
    const transactions: ParsedTransaction[] = []

    // Regex patterns for extracting monetary amounts — matches Android's SpendingParser
    const amountPatterns = [
      // INR patterns: "Rs.1,234.56" or "Rs 1234"
      /(?:rs\.?|₹|inr)\s*([0-9,]+(?:\.\d{1,2})?)/i,
      // USD patterns: "$1,234.56"
      /(?:\$|usd)\s*([0-9,]+(?:\.\d{1,2})?)/i,
      // Generic "amount: 1,234.56" pattern
      /amount[: ]*([0-9,]+(?:\.\d{1,2})?)/i,
    ]

    for (const msg of messages) {
      const bodyLower = msg.body.toLowerCase()

      // Skip credits/refunds (same as Android — no debit keyword requirement)
      if (bodyLower.includes('credited') || bodyLower.includes('refund') ||
          bodyLower.includes('reversal') || bodyLower.includes('deposit')) {
        continue
      }

      // Extract merchant first (needed for currency detection)
      const merchant = extractMerchantFromMessage(msg.body)

      // Extract amount
      let amount: number | null = null

      for (const pattern of amountPatterns) {
        const match = msg.body.match(pattern)
        if (match) {
          const amountStr = match[1].replace(/,/g, '')
          amount = parseFloat(amountStr)
          if (!isNaN(amount)) {
            break
          }
        }
      }

      // Skip invalid amounts or likely reference numbers
      if (amount === null || amount <= 0 || amount > 10000000) {
        continue
      }

      // Detect currency based on merchant, message content, and sender
      const currency = detectCurrency(msg.body, merchant, msg.address)

      transactions.push({
        amount,
        currency,
        merchant,
        date: msg.date,
        message: msg.body,
      })
    }

    return transactions.sort((a, b) => b.date - a.date)
  }, [messages])

  /**
   * Parses messages to extract bill and payment reminders
   * Memoized for performance optimization
   *
   * EXTRACTED DATA:
   * - Bill type (Credit Card, Utility, Subscription, Loan, Insurance)
   * - Due date (parsed from various date formats)
   * - Amount due
   * - Overdue status (compared against current date)
   *
   * @returns Array of BillReminder objects sorted by due date (upcoming first)
   */
  const parseBills = useMemo((): BillReminder[] => {
    const bills: BillReminder[] = []
    const billKeywords = ['due', 'payment due', 'bill', 'minimum payment', 'pay by', 'statement', 'balance due', 'amount due']

    for (const msg of messages) {
      const bodyLower = msg.body.toLowerCase()

      // Check if it's a bill-related message
      if (!billKeywords.some(kw => bodyLower.includes(kw))) {
        continue
      }

      // Determine bill type
      let billType = 'Other'
      if (bodyLower.includes('credit card') || bodyLower.includes('card payment')) {
        billType = 'Credit Card'
      } else if (bodyLower.includes('electricity') || bodyLower.includes('water') || bodyLower.includes('gas') || bodyLower.includes('utility')) {
        billType = 'Utility'
      } else if (bodyLower.includes('subscription') || bodyLower.includes('membership')) {
        billType = 'Subscription'
      } else if (bodyLower.includes('loan') || bodyLower.includes('emi')) {
        billType = 'Loan'
      } else if (bodyLower.includes('insurance')) {
        billType = 'Insurance'
      }

      // Extract due date
      let dueDate: Date | null = null
      const dueDatePatterns = [
        /(?:due|pay by)[:\s]*(\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?)/i,
        /(?:due|pay by)[:\s]*(\d{1,2}\s*(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*)/i,
      ]
      for (const pattern of dueDatePatterns) {
        const match = msg.body.match(pattern)
        if (match) {
          const parsed = new Date(match[1])
          if (!isNaN(parsed.getTime())) {
            dueDate = parsed
            break
          }
        }
      }

      // Extract amount
      let amount: number | null = null
      let currency = 'USD'
      const amountPattern = /(?:minimum|min|amount|balance|due)[:\s]*(?:\$|Rs\.?|₹)?\s*([0-9,]+(?:\.\d{2})?)/i
      const amountMatch = msg.body.match(amountPattern)
      if (amountMatch) {
        amount = parseFloat(amountMatch[1].replace(/,/g, ''))
        currency = (msg.body.includes('Rs') || msg.body.includes('₹')) ? 'INR' : 'USD'
      }

      // Extract merchant/institution
      const merchant = extractMerchantFromMessage(msg.body) || 'Unknown'

      bills.push({
        billType,
        dueDate,
        amount,
        currency,
        merchant,
        message: msg.body,
        date: msg.date,
        isOverdue: dueDate ? dueDate < new Date() : false,
      })
    }

    // Sort by due date (upcoming first)
    return bills.sort((a, b) => {
      if (a.dueDate && b.dueDate) return a.dueDate.getTime() - b.dueDate.getTime()
      if (a.dueDate) return -1
      return 1
    })
  }, [messages])

  /**
   * Parses messages to extract account balance information
   * Memoized for performance optimization
   *
   * Handles various bank SMS formats including:
   * - "Avl Bal: $1,234.56"
   * - "Available Balance: Rs.1,234.56"
   * - Balance notifications from multiple institutions
   *
   * Deduplication: Keeps only the most recent balance per institution/account type
   *
   * @returns Array of BalanceInfo objects (deduplicated by institution)
   */
  const parseBalances = useMemo((): BalanceInfo[] => {
    const balances: BalanceInfo[] = []
    const balanceKeywords = ['balance', 'avl bal', 'avl', 'available', 'a/c bal', 'current bal']

    for (const msg of messages) {
      const bodyLower = msg.body.toLowerCase()

      // Check if it's a balance-related message
      if (!balanceKeywords.some(kw => bodyLower.includes(kw))) {
        continue
      }

      // Multiple patterns to extract balance amount
      let balance: number | null = null
      let currency = 'USD'

      // Various balance patterns found in bank SMS
      const balancePatterns = [
        // "Avl Bal: $1,234.56" or "Available Balance: Rs.1,234.56"
        /(?:avl\.?\s*bal\.?|available\s*(?:bal\.?|balance)?|current\s*bal\.?|a\/c\s*bal\.?|balance)[:\s]*(?:is\s*)?(?:\$|Rs\.?|₹|INR|USD)?\s*([0-9,]+(?:\.\d{1,2})?)/i,
        // "$1,234.56 available" or "Rs.1,234.56 balance"
        /(?:\$|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)\s*(?:available|avl|balance)/i,
        // "Balance $1,234.56"
        /balance[:\s]+(?:\$|Rs\.?|₹)?\s*([0-9,]+(?:\.\d{1,2})?)/i,
        // Just find currency + amount near balance keyword
        /(?:\$|Rs\.?|₹)\s*([0-9,]+(?:\.\d{1,2})?)/i,
      ]

      for (const pattern of balancePatterns) {
        const match = msg.body.match(pattern)
        if (match && match[1]) {
          const parsed = parseFloat(match[1].replace(/,/g, ''))
          // Sanity check - balance should be reasonable (not a date or ref number)
          if (!isNaN(parsed) && parsed > 0 && parsed < 10000000) {
            balance = parsed
            break
          }
        }
      }

      // Determine currency
      if (msg.body.includes('Rs') || msg.body.includes('₹') || msg.body.includes('INR')) {
        currency = 'INR'
      } else if (msg.body.includes('$') || msg.body.includes('USD')) {
        currency = 'USD'
      }

      // Determine account type
      let accountType = 'Account'
      if (bodyLower.includes('credit card') || bodyLower.includes('card ending')) accountType = 'Credit Card'
      else if (bodyLower.includes('saving')) accountType = 'Savings'
      else if (bodyLower.includes('checking') || bodyLower.includes('current a/c')) accountType = 'Checking'
      else if (bodyLower.includes('debit')) accountType = 'Debit Card'

      // Extract institution from sender or message
      let institution = extractMerchantFromMessage(msg.body)

      // Also check sender address for bank names
      if (!institution) {
        const senderLower = msg.address.toLowerCase()
        const bankNames = ['chase', 'bofa', 'wellsfargo', 'citi', 'amex', 'discover', 'capital', 'usbank', 'pnc', 'td', 'ally', 'paypal']
        for (const bank of bankNames) {
          if (senderLower.includes(bank)) {
            institution = bank.charAt(0).toUpperCase() + bank.slice(1)
            break
          }
        }
      }

      balances.push({
        accountType,
        balance,
        currency,
        institution,
        message: msg.body,
        date: msg.date,
      })
    }

    // Remove duplicates (same institution, keep most recent)
    const seen = new Map<string, BalanceInfo>()
    for (const bal of balances.sort((a, b) => b.date - a.date)) {
      const key = `${bal.institution || 'unknown'}-${bal.accountType}`
      if (!seen.has(key)) {
        seen.set(key, bal)
      }
    }

    return Array.from(seen.values())
  }, [messages])

  /**
   * Detects recurring expenses and subscriptions from transaction patterns
   * Memoized and depends on parseTransactions
   *
   * DETECTION ALGORITHM:
   * 1. Group transactions by merchant
   * 2. Filter merchants with 2+ transactions
   * 3. Check if amounts are similar (within 10% variance)
   * 4. Analyze charge intervals to determine frequency
   * 5. Known subscription services (Netflix, Spotify, etc.) get priority matching
   *
   * FREQUENCY DETECTION:
   * - Weekly: 5-10 day intervals
   * - Monthly: 25-35 day intervals
   * - Yearly: 350-380 day intervals
   *
   * @returns Array of RecurringExpense objects sorted by amount (highest first)
   */
  const detectRecurringExpenses = useMemo((): RecurringExpense[] => {
    const transactions = parseTransactions
    const merchantGroups: Record<string, ParsedTransaction[]> = {}

    // Group by merchant
    transactions.forEach(txn => {
      const merchant = txn.merchant?.toLowerCase() || 'unknown'
      if (!merchantGroups[merchant]) {
        merchantGroups[merchant] = []
      }
      merchantGroups[merchant].push(txn)
    })

    const recurring: RecurringExpense[] = []

    // Known subscription services
    const subscriptionKeywords = ['netflix', 'spotify', 'hulu', 'disney', 'prime', 'apple', 'google', 'youtube', 'hbo', 'paramount', 'peacock', 'audible', 'dropbox', 'icloud', 'adobe', 'microsoft', 'gym', 'fitness', 'membership']

    for (const [merchant, txns] of Object.entries(merchantGroups)) {
      if (txns.length < 2) continue

      // Check if it's a known subscription
      const isKnownSubscription = subscriptionKeywords.some(kw => merchant.includes(kw))

      // Sort by date
      const sorted = txns.sort((a, b) => a.date - b.date)

      // Check if amounts are similar (within 10%)
      const amounts = sorted.map(t => t.amount)
      const avgAmount = amounts.reduce((a, b) => a + b, 0) / amounts.length
      const similarAmounts = amounts.every(a => Math.abs(a - avgAmount) / avgAmount < 0.1)

      if (!similarAmounts && !isKnownSubscription) continue

      // Check intervals between charges
      const intervals: number[] = []
      for (let i = 1; i < sorted.length; i++) {
        const daysDiff = (sorted[i].date - sorted[i-1].date) / (1000 * 60 * 60 * 24)
        intervals.push(daysDiff)
      }

      if (intervals.length === 0) continue

      const avgInterval = intervals.reduce((a, b) => a + b, 0) / intervals.length

      // Determine frequency
      let frequency: 'monthly' | 'weekly' | 'yearly' = 'monthly'
      if (avgInterval >= 5 && avgInterval <= 10) {
        frequency = 'weekly'
      } else if (avgInterval >= 25 && avgInterval <= 35) {
        frequency = 'monthly'
      } else if (avgInterval >= 350 && avgInterval <= 380) {
        frequency = 'yearly'
      } else if (!isKnownSubscription) {
        continue // Not a regular interval
      }

      recurring.push({
        merchant: merchant.charAt(0).toUpperCase() + merchant.slice(1),
        amount: avgAmount,
        currency: sorted[0].currency,
        frequency,
        lastCharge: sorted[sorted.length - 1].date,
        occurrences: sorted.length,
      })
    }

    return recurring.sort((a, b) => b.amount - a.amount)
  }, [parseTransactions])

  /**
   * Generates a comprehensive financial digest/summary
   * Memoized and aggregates data from multiple parsed sources
   *
   * INCLUDED METRICS:
   * - Total spending this month vs last month
   * - Month-over-month spending change percentage
   * - Transaction count
   * - Upcoming bills count
   * - Recent package deliveries
   * - Estimated monthly subscription cost
   * - Top spending merchant
   *
   * @returns SmartDigest object with aggregated financial metrics
   */
  const generateSmartDigest = useMemo((): SmartDigest => {
    const transactions = parseTransactions
    const now = Date.now()
    const thisMonthStart = new Date(new Date().getFullYear(), new Date().getMonth(), 1).getTime()
    const lastMonthStart = new Date(new Date().getFullYear(), new Date().getMonth() - 1, 1).getTime()

    const thisMonthTxns = transactions.filter(t => t.date >= thisMonthStart)
    const lastMonthTxns = transactions.filter(t => t.date >= lastMonthStart && t.date < thisMonthStart)

    const totalThisMonth = thisMonthTxns.reduce((sum, t) => sum + t.amount, 0)
    const totalLastMonth = lastMonthTxns.reduce((sum, t) => sum + t.amount, 0)

    // Top merchant this month
    const merchantTotals: Record<string, number> = {}
    thisMonthTxns.forEach(t => {
      const m = t.merchant || 'Unknown'
      merchantTotals[m] = (merchantTotals[m] || 0) + t.amount
    })
    const topMerchant = Object.entries(merchantTotals).sort((a, b) => b[1] - a[1])[0]?.[0] || null

    // Subscription total
    const subscriptionTotal = detectRecurringExpenses.reduce((sum, r) => {
      if (r.frequency === 'monthly') return sum + r.amount
      if (r.frequency === 'yearly') return sum + r.amount / 12
      if (r.frequency === 'weekly') return sum + r.amount * 4
      return sum
    }, 0)

    return {
      totalSpentThisMonth: totalThisMonth,
      totalSpentLastMonth: totalLastMonth,
      spendingChange: totalLastMonth > 0 ? ((totalThisMonth - totalLastMonth) / totalLastMonth) * 100 : 0,
      transactionCount: thisMonthTxns.length,
      upcomingBills: parseBills.filter(b => !b.isOverdue && b.dueDate && b.dueDate > new Date()).length,
      recentPackages: messages.filter(m => {
        const bodyLower = m.body.toLowerCase()
        return (bodyLower.includes('shipped') || bodyLower.includes('delivery') || bodyLower.includes('arriving')) &&
               m.date > now - 7 * 24 * 60 * 60 * 1000
      }).length,
      subscriptionTotal,
      topMerchant,
      currency: transactions[0]?.currency || 'USD',
    }
  }, [parseTransactions, parseBills, detectRecurringExpenses, messages])

  /**
   * Filters transactions by merchant name
   * Matches against both extracted merchant name and raw message body
   *
   * @param transactions - Array of transactions to filter
   * @param merchant - Merchant name to filter by
   * @returns Filtered array of transactions
   */
  const filterByMerchant = (transactions: ParsedTransaction[], merchant: string): ParsedTransaction[] => {
    const lowerMerchant = merchant.toLowerCase()
    return transactions.filter(txn =>
      txn.merchant?.toLowerCase().includes(lowerMerchant) ||
      txn.message.toLowerCase().includes(lowerMerchant)
    )
  }

  /**
   * Applies time-based filtering to transactions based on query keywords
   * Supports: today, week/7 day, month/30 day, year
   *
   * @param transactions - Array of transactions to filter
   * @param query - User query containing time keywords
   * @returns Filtered array of transactions within the time period
   */
  const applyTimeFilter = (transactions: ParsedTransaction[], query: string): ParsedTransaction[] => {
    const lowerQuery = query.toLowerCase()
    const now = Date.now()

    if (lowerQuery.includes('today')) {
      const dayStart = new Date().setHours(0, 0, 0, 0)
      return transactions.filter(t => t.date >= dayStart)
    }
    if (lowerQuery.includes('week') || lowerQuery.includes('7 day')) {
      return transactions.filter(t => t.date >= now - 7 * 24 * 60 * 60 * 1000)
    }
    if (lowerQuery.includes('month') || lowerQuery.includes('30 day')) {
      const monthStart = new Date(new Date().getFullYear(), new Date().getMonth(), 1).getTime()
      return transactions.filter(t => t.date >= monthStart)
    }
    if (lowerQuery.includes('year')) {
      const yearStart = new Date(new Date().getFullYear(), 0, 1).getTime()
      return transactions.filter(t => t.date >= yearStart)
    }
    return transactions
  }

  /**
   * Converts time-related keywords in query to human-readable labels
   *
   * @param query - User query to analyze
   * @returns Human-readable time period label (e.g., "This Month")
   */
  const getTimePeriodLabel = (query: string): string => {
    const lowerQuery = query.toLowerCase()
    if (lowerQuery.includes('today')) return 'Today'
    if (lowerQuery.includes('week') || lowerQuery.includes('7 day')) return 'This Week'
    if (lowerQuery.includes('month') || lowerQuery.includes('30 day')) return 'This Month'
    if (lowerQuery.includes('year')) return 'This Year'
    return ''
  }

  /**
   * Generates contextual follow-up query suggestions based on the assistant's response
   * Analyzes response content to suggest related queries the user might want to ask
   *
   * @param responseContent - The assistant's response text
   * @returns Array of up to 3 suggested follow-up queries
   */
  const getSuggestedFollowUps = (responseContent: string): string[] => {
    const suggestions: string[] = []

    // Based on what topic was discussed, suggest related queries
    if (responseContent.includes('Spending') || responseContent.includes('spent') || responseContent.includes('transactions')) {
      if (!responseContent.includes('category')) suggestions.push('Show by category')
      if (!responseContent.includes('Top Merchants')) suggestions.push('Top merchants')
      if (!responseContent.includes('vs last month')) suggestions.push('Compare to last month')
      suggestions.push('Show subscriptions')
    }

    if (responseContent.includes('bill') || responseContent.includes('due')) {
      suggestions.push('Show my spending')
      suggestions.push('Account balances')
    }

    if (responseContent.includes('balance') || responseContent.includes('Balance')) {
      suggestions.push('Show spending this month')
      suggestions.push('Upcoming bills')
    }

    if (responseContent.includes('subscription') || responseContent.includes('recurring')) {
      suggestions.push('Total spending')
      suggestions.push('Show bills')
    }

    if (responseContent.includes('package') || responseContent.includes('delivery')) {
      suggestions.push('Show spending')
      suggestions.push('My orders')
    }

    if (responseContent.includes('OTP') || responseContent.includes('code')) {
      suggestions.push('Show spending')
      suggestions.push('Account balance')
    }

    // Default suggestions if nothing specific
    if (suggestions.length === 0) {
      suggestions.push('Show spending this month')
      suggestions.push('Upcoming bills')
      suggestions.push('Track packages')
    }

    // Limit to 3 suggestions
    return suggestions.slice(0, 3)
  }

  /**
   * Legacy function maintained for backward compatibility
   * @deprecated Use parseTransactions directly
   */
  const analyzeSpending = () => parseTransactions

  /**
   * Formats a Unix timestamp into a readable date string
   *
   * @param timestamp - Unix timestamp in milliseconds
   * @returns Formatted date string (e.g., "Jan 15, 2024")
   */
  const formatDate = (timestamp: number): string => {
    const date = new Date(timestamp)
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  }

  /**
   * Formats a numeric amount with the appropriate currency symbol
   *
   * @param amount - Numeric amount to format
   * @param currency - Currency code ('USD' or 'INR')
   * @returns Formatted currency string (e.g., "$1,234.56" or "Rs.1,234.56")
   */
  const formatCurrency = (amount: number, currency: string): string => {
    const symbol = currency === 'USD' ? '$' : '₹'
    return `${symbol}${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
  }

  /**
   * Main response generation function - the "brain" of the AI assistant
   * Analyzes the user's query and returns an appropriate response
   *
   * SUPPORTED QUERY TYPES:
   * - Merchant-specific spending (e.g., "Amazon spending")
   * - Transaction listing
   * - OTP/verification code lookup
   * - Top merchants analysis
   * - Category-based spending breakdown
   * - Subscription/recurring expense detection
   * - Financial summary/digest
   * - Spending trends and comparisons
   * - Message search
   * - Bill and payment reminders
   * - Account balance lookup
   * - Package/delivery tracking
   * - General spending analysis
   *
   * @param userQuery - The user's natural language query
   * @returns Formatted response string with analysis results
   */
  const generateResponse = (userQuery: string): string => {
    const lowerQuery = userQuery.toLowerCase()

    // Extract merchant from query first
    const queryMerchant = extractMerchantFromQuery(userQuery)

    // Merchant-specific spending query (e.g., "Amazon spending", "spent at Amazon")
    if (queryMerchant && (lowerQuery.includes('spend') || lowerQuery.includes('spent') ||
        lowerQuery.includes('transaction') || lowerQuery.includes('purchase'))) {
      const merchantTransactions = filterByMerchant(parseTransactions, queryMerchant)

      if (merchantTransactions.length === 0) {
        const merchantName = queryMerchant.charAt(0).toUpperCase() + queryMerchant.slice(1)
        return `📊 No spending transactions found for ${merchantName}.\n\nThis could mean:\n• No ${merchantName} purchases in your SMS history\n• Purchases were made via a different payment method\n• SMS notifications were not enabled`
      }

      // Apply time filter
      const filtered = applyTimeFilter(merchantTransactions, userQuery)
      const total = filtered.reduce((sum, t) => sum + t.amount, 0)
      const currency = filtered[0]?.currency || 'INR'
      const periodLabel = getTimePeriodLabel(userQuery)
      const merchantName = queryMerchant.charAt(0).toUpperCase() + queryMerchant.slice(1)

      let response = `💳 ${merchantName} Spending${periodLabel ? ` (${periodLabel})` : ''}\n\n`
      response += `Total: ${formatCurrency(total, currency)}\n`
      response += `Transactions: ${filtered.length}\n\n`

      if (filtered.length > 0) {
        response += `📝 Details:\n`
        filtered.slice(0, 10).forEach((t, i) => {
          const preview = t.message.substring(0, 70).replace(/\n/g, ' ')
          response += `${i + 1}. ${formatCurrency(t.amount, t.currency)} — ${formatDate(t.date)}\n   ${preview}...\n\n`
        })
      }

      return response.trim()
    }

    // List transactions
    if (lowerQuery.includes('list') && (lowerQuery.includes('transaction') || lowerQuery.includes('spending') || lowerQuery.includes('purchase'))) {
      const transactions = parseTransactions
      if (transactions.length === 0) {
        return "📊 No spending transactions found in your messages.\n\nMake sure you have SMS notifications enabled for your bank/payment apps."
      }

      const filtered = applyTimeFilter(transactions, userQuery)
      const limit = lowerQuery.includes('all') ? filtered.length : Math.min(10, filtered.length)
      const displayTransactions = filtered.slice(0, limit)

      let response = `💳 Found ${filtered.length} transactions. Here are the ${limit === filtered.length ? 'all' : 'most recent'}:\n\n`
      displayTransactions.forEach((t, i) => {
        const preview = t.message.substring(0, 60).replace(/\n/g, ' ')
        response += `${i + 1}. ${formatCurrency(t.amount, t.currency)} - ${formatDate(t.date)}${t.merchant ? ` at ${t.merchant}` : ''}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Find OTPs
    if (lowerQuery.includes('otp') || lowerQuery.includes('verification code') || (lowerQuery.includes('code') && !lowerQuery.includes('zip'))) {
      const otpPattern = /\b\d{4,6}\b/g
      const recentMessages = messages.slice(0, 50)
      const otps: Array<{code: string, date: number, message: string}> = []

      recentMessages.forEach(msg => {
        const bodyLower = msg.body.toLowerCase()
        if (bodyLower.includes('otp') ||
            bodyLower.includes('verification') ||
            (bodyLower.includes('code') && !bodyLower.includes('zip code'))) {
          const matches = msg.body.match(otpPattern)
          if (matches) {
            matches.forEach(code => {
              otps.push({ code, date: msg.date, message: msg.body })
            })
          }
        }
      })

      if (otps.length === 0) {
        return "🔐 No OTP codes found in recent messages."
      }

      let response = `🔐 Found ${otps.length} OTP code(s):\n\n`
      otps.slice(0, 5).forEach((otp, i) => {
        const preview = otp.message.substring(0, 50).replace(/\n/g, ' ')
        response += `${i + 1}. Code: ${otp.code} - ${formatDate(otp.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Top merchants/businesses
    if (lowerQuery.includes('top merchant') || lowerQuery.includes('where do i spend') || lowerQuery.includes('most spent at')) {
      const transactions = parseTransactions
      const merchantTotals: Record<string, { amount: number, currency: string }> = {}

      transactions.forEach(t => {
        const merchant = t.merchant || 'Unknown'
        if (!merchantTotals[merchant]) {
          merchantTotals[merchant] = { amount: 0, currency: t.currency }
        }
        merchantTotals[merchant].amount += t.amount
      })

      const sorted = Object.entries(merchantTotals)
        .sort((a, b) => b[1].amount - a[1].amount)
        .slice(0, 8)

      if (sorted.length === 0) {
        return "🏪 Could not identify specific merchants from your transaction messages."
      }

      let response = `🏪 Your top merchants by spending:\n\n`
      sorted.forEach(([merchant, data], i) => {
        response += `${i + 1}. ${merchant}: ${formatCurrency(data.amount, data.currency)}\n`
      })
      return response
    }

    // Spending by category
    if (lowerQuery.includes('category') || lowerQuery.includes('categories') || lowerQuery.includes('breakdown')) {
      const transactions = parseTransactions
      const categories: Record<string, { amount: number, currency: string }> = {
        'Shopping': { amount: 0, currency: 'INR' },
        'Food & Dining': { amount: 0, currency: 'INR' },
        'Bills & Utilities': { amount: 0, currency: 'INR' },
        'Travel': { amount: 0, currency: 'INR' },
        'Entertainment': { amount: 0, currency: 'INR' },
        'Other': { amount: 0, currency: 'INR' }
      }

      transactions.forEach(t => {
        const msg = t.message.toLowerCase()
        let category = 'Other'

        if (msg.includes('amazon') || msg.includes('flipkart') || msg.includes('shop') || msg.includes('store') || msg.includes('retail') || msg.includes('myntra')) {
          category = 'Shopping'
        } else if (msg.includes('food') || msg.includes('restaurant') || msg.includes('dining') || msg.includes('swiggy') || msg.includes('zomato') || msg.includes('doordash')) {
          category = 'Food & Dining'
        } else if (msg.includes('bill') || msg.includes('electric') || msg.includes('water') || msg.includes('internet') || msg.includes('utility') || msg.includes('recharge')) {
          category = 'Bills & Utilities'
        } else if (msg.includes('uber') || msg.includes('lyft') || msg.includes('ola') || msg.includes('flight') || msg.includes('hotel') || msg.includes('travel')) {
          category = 'Travel'
        } else if (msg.includes('movie') || msg.includes('game') || msg.includes('spotify') || msg.includes('netflix') || msg.includes('entertainment')) {
          category = 'Entertainment'
        }

        categories[category].amount += t.amount
        categories[category].currency = t.currency
      })

      const total = Object.values(categories).reduce((sum, val) => sum + val.amount, 0)
      let response = `📊 Spending by category:\n\n`

      Object.entries(categories)
        .filter(([_, data]) => data.amount > 0)
        .sort((a, b) => b[1].amount - a[1].amount)
        .forEach(([cat, data]) => {
          const percentage = total > 0 ? ((data.amount / total) * 100).toFixed(1) : '0'
          response += `${cat}: ${formatCurrency(data.amount, data.currency)} (${percentage}%)\n`
        })

      return response
    }

    // Subscriptions / Recurring expenses
    if (lowerQuery.includes('subscription') || lowerQuery.includes('recurring') || lowerQuery.includes('monthly charge')) {
      const recurring = detectRecurringExpenses

      if (recurring.length === 0) {
        return "🔄 No recurring subscriptions detected.\n\nI look for charges that repeat at regular intervals (weekly, monthly, yearly) from the same merchant."
      }

      const monthlyTotal = recurring.reduce((sum, r) => {
        if (r.frequency === 'monthly') return sum + r.amount
        if (r.frequency === 'yearly') return sum + r.amount / 12
        if (r.frequency === 'weekly') return sum + r.amount * 4
        return sum
      }, 0)

      let response = `🔄 Detected ${recurring.length} subscription(s):\n\n`
      response += `📊 Estimated Monthly Total: ${formatCurrency(monthlyTotal, recurring[0]?.currency || 'USD')}\n\n`

      recurring.forEach((sub, i) => {
        const freqLabel = sub.frequency === 'monthly' ? '/mo' : sub.frequency === 'yearly' ? '/yr' : '/wk'
        response += `${i + 1}. ${sub.merchant}\n`
        response += `   ${formatCurrency(sub.amount, sub.currency)}${freqLabel} • ${sub.occurrences} charges detected\n`
        response += `   Last charged: ${formatDate(sub.lastCharge)}\n\n`
      })

      return response.trim()
    }

    // Smart summary / digest
    if (lowerQuery.includes('summary') || lowerQuery.includes('digest') || lowerQuery.includes('overview') || lowerQuery.includes('snapshot')) {
      const digest = generateSmartDigest

      let response = `📊 Your Financial Snapshot\n\n`

      // Spending this month
      response += `💰 This Month: ${formatCurrency(digest.totalSpentThisMonth, digest.currency)}\n`
      if (digest.totalSpentLastMonth > 0) {
        const arrow = digest.spendingChange > 0 ? '📈' : '📉'
        const sign = digest.spendingChange > 0 ? '+' : ''
        response += `   ${arrow} ${sign}${digest.spendingChange.toFixed(1)}% vs last month\n`
      }
      response += `   ${digest.transactionCount} transactions\n\n`

      // Top merchant
      if (digest.topMerchant) {
        response += `🏪 Top Merchant: ${digest.topMerchant}\n\n`
      }

      // Subscriptions
      if (digest.subscriptionTotal > 0) {
        response += `🔄 Subscriptions: ~${formatCurrency(digest.subscriptionTotal, digest.currency)}/month\n\n`
      }

      // Bills & Packages
      if (digest.upcomingBills > 0) {
        response += `📅 Upcoming Bills: ${digest.upcomingBills}\n`
      }
      if (digest.recentPackages > 0) {
        response += `📦 Recent Packages: ${digest.recentPackages}\n`
      }

      return response.trim()
    }

    // Spending trends / compare
    if (lowerQuery.includes('trend') || lowerQuery.includes('compare') || lowerQuery.includes('vs last')) {
      const transactions = parseTransactions
      const now = new Date()
      const thisMonthStart = new Date(now.getFullYear(), now.getMonth(), 1).getTime()
      const lastMonthStart = new Date(now.getFullYear(), now.getMonth() - 1, 1).getTime()

      const thisMonth = transactions.filter(t => t.date >= thisMonthStart)
      const lastMonth = transactions.filter(t => t.date >= lastMonthStart && t.date < thisMonthStart)

      const thisMonthTotal = thisMonth.reduce((sum, t) => sum + t.amount, 0)
      const lastMonthTotal = lastMonth.reduce((sum, t) => sum + t.amount, 0)
      const currency = transactions[0]?.currency || 'INR'

      const diff = thisMonthTotal - lastMonthTotal
      const percentChange = lastMonthTotal > 0 ? ((diff / lastMonthTotal) * 100).toFixed(1) : '0'
      const trend = diff > 0 ? '📈 increased' : '📉 decreased'

      return `📊 Spending Trends:\n\nThis Month: ${formatCurrency(thisMonthTotal, currency)} (${thisMonth.length} transactions)\nLast Month: ${formatCurrency(lastMonthTotal, currency)} (${lastMonth.length} transactions)\n\nYour spending has ${trend} by ${formatCurrency(Math.abs(diff), currency)} (${percentChange}%)`
    }

    // Search messages
    if (lowerQuery.startsWith('search') || lowerQuery.startsWith('find messages')) {
      const searchTerm = lowerQuery.replace(/^(search|find messages)\s+(for\s+)?/i, '').trim()
      if (!searchTerm) {
        return "Please specify what to search for. Example: 'search amazon'"
      }

      const results = messages.filter(msg =>
        msg.body.toLowerCase().includes(searchTerm)
      ).slice(0, 5)

      if (results.length === 0) {
        return `🔍 No messages found containing "${searchTerm}"`
      }

      let response = `🔍 Found ${results.length} message(s) containing "${searchTerm}":\n\n`
      results.forEach((msg, i) => {
        const preview = msg.body.substring(0, 80).replace(/\n/g, ' ')
        response += `${i + 1}. From ${msg.address} - ${formatDate(msg.date)}\n   ${preview}...\n\n`
      })
      return response.trim()
    }

    // Upcoming bills
    if (lowerQuery.includes('bill') || lowerQuery.includes('due') || lowerQuery.includes('payment due') || lowerQuery.includes('upcoming')) {
      const bills = parseBills

      if (bills.length === 0) {
        return "📅 No bill reminders found in your messages.\n\nMake sure you have SMS notifications enabled for your credit cards and utility providers."
      }

      let response = `📅 Found ${bills.length} bill-related message(s):\n\n`
      bills.slice(0, 8).forEach((bill, i) => {
        const status = bill.isOverdue ? '⚠️ OVERDUE' : bill.dueDate ? '📆 Due' : ''
        const dueDateStr = bill.dueDate ? bill.dueDate.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) : 'Unknown'
        const amountStr = bill.amount ? formatCurrency(bill.amount, bill.currency) : 'Amount not found'

        response += `${i + 1}. ${bill.billType} - ${bill.merchant}\n`
        response += `   ${status} ${dueDateStr} • ${amountStr}\n`
        response += `   ${bill.message.substring(0, 60).replace(/\n/g, ' ')}...\n\n`
      })

      return response.trim()
    }

    // Account balance
    if (lowerQuery.includes('balance') || lowerQuery.includes('account balance') || lowerQuery.includes('bank balance')) {
      const balances = parseBalances

      if (balances.length === 0) {
        return "💳 No account balance information found in your messages.\n\nMake sure you have SMS notifications enabled for your bank accounts."
      }

      // Filter to only show balances with actual amounts
      const withAmounts = balances.filter(b => b.balance !== null && b.balance > 0)

      let response = `💳 Found ${balances.length} account balance(s):\n\n`

      if (withAmounts.length > 0) {
        withAmounts.slice(0, 6).forEach((bal, i) => {
          const balanceStr = formatCurrency(bal.balance!, bal.currency)
          const dateStr = formatDate(bal.date)
          const name = bal.institution || bal.accountType

          response += `${i + 1}. ${name}\n`
          response += `   💰 ${balanceStr}\n`
          response += `   ${bal.accountType} • Updated ${dateStr}\n\n`
        })
      } else {
        // Show what we found but couldn't extract amounts
        balances.slice(0, 5).forEach((bal, i) => {
          const name = bal.institution || bal.accountType
          response += `${i + 1}. ${name} - ${bal.accountType}\n`
          response += `   ${bal.message.substring(0, 80).replace(/\n/g, ' ')}...\n\n`
        })
        response += "\n⚠️ Could not extract exact balance amounts from messages."
      }

      return response.trim()
    }

    // Currency totals
    if (lowerQuery.includes('money total') || lowerQuery.includes('currency total') ||
        lowerQuery.includes('money summary') || lowerQuery.includes('financial summary') ||
        lowerQuery.includes('show money') || lowerQuery.includes('currency breakdown')) {

      const currencyTotals: Record<string, number> = {}

      // Currency patterns with symbol and text detection
      const currencyPatterns: Record<string, RegExp[]> = {
        'USD': [
          /\$\s*([0-9,]+(?:\.\d{1,2})?)/g,
          /([0-9,]+(?:\.\d{1,2})?)\s*USD/gi
        ],
        'EUR': [
          /€\s*([0-9,]+(?:\.\d{1,2})?)/g,
          /([0-9,]+(?:\.\d{1,2})?)\s*EUR/gi
        ],
        'GBP': [
          /£\s*([0-9,]+(?:\.\d{1,2})?)/g,
          /([0-9,]+(?:\.\d{1,2})?)\s*GBP/gi
        ],
        'JPY': [
          /¥\s*([0-9,]+(?:\.\d{1,2})?)/g,
          /([0-9,]+(?:\.\d{1,2})?)\s*JPY/gi
        ],
        'INR': [
          /₹\s*([0-9,]+(?:\.\d{1,2})?)/g,
          /(?:Rs\.?|INR)\s*([0-9,]+(?:\.\d{1,2})?)/gi,
          /([0-9,]+(?:\.\d{1,2})?)\s*(?:Rs\.?|INR)/gi
        ],
        'CAD': [
          /CA\$\s*([0-9,]+(?:\.\d{1,2})?)/gi,
          /([0-9,]+(?:\.\d{1,2})?)\s*CAD/gi
        ],
        'AUD': [
          /AU\$\s*([0-9,]+(?:\.\d{1,2})?)/gi,
          /([0-9,]+(?:\.\d{1,2})?)\s*AUD/gi
        ],
        'NZD': [
          /NZ\$\s*([0-9,]+(?:\.\d{1,2})?)/gi,
          /([0-9,]+(?:\.\d{1,2})?)\s*NZD/gi
        ],
        'CHF': [
          /([0-9,]+(?:\.\d{1,2})?)\s*CHF/gi,
          /CHF\s*([0-9,]+(?:\.\d{1,2})?)/gi
        ],
        'SEK': [
          /([0-9,]+(?:\.\d{1,2})?)\s*SEK/gi,
          /SEK\s*([0-9,]+(?:\.\d{1,2})?)/gi
        ],
        'NOK': [
          /([0-9,]+(?:\.\d{1,2})?)\s*NOK/gi,
          /NOK\s*([0-9,]+(?:\.\d{1,2})?)/gi
        ],
        'DKK': [
          /([0-9,]+(?:\.\d{1,2})?)\s*DKK/gi,
          /DKK\s*([0-9,]+(?:\.\d{1,2})?)/gi
        ]
      }

      // Scan all messages for currency mentions
      messages.forEach(msg => {
        Object.entries(currencyPatterns).forEach(([currency, patterns]) => {
          patterns.forEach(pattern => {
            // Reset regex lastIndex for global patterns
            pattern.lastIndex = 0
            let match
            while ((match = pattern.exec(msg.body)) !== null) {
              if (match[1]) {
                const amountStr = match[1].replace(/,/g, '')
                const amount = parseFloat(amountStr)

                // Validate amount (positive, reasonable range)
                if (!isNaN(amount) && amount > 0 && amount < 10_000_000) {
                  currencyTotals[currency] = (currencyTotals[currency] || 0) + amount
                }
              }
            }
          })
        })
      })

      if (Object.keys(currencyTotals).length === 0) {
        return "💰 Currency Totals\n\nNo currency amounts found in your messages.\n\nI can detect amounts in:\n• $, €, £, ¥, ₹\n• CA$, AU$, NZ$\n• CHF, SEK, NOK, DKK"
      }

      // Get currency symbol for display
      const getCurrencySymbol = (code: string): string => {
        const symbols: Record<string, string> = {
          'USD': '$', 'EUR': '€', 'GBP': '£', 'JPY': '¥', 'INR': '₹',
          'CAD': 'CA$', 'AUD': 'AU$', 'NZD': 'NZ$',
          'CHF': 'CHF ', 'SEK': 'SEK ', 'NOK': 'NOK ', 'DKK': 'DKK '
        }
        return symbols[code] || ''
      }

      // Build formatted response
      const sortedTotals = Object.entries(currencyTotals).sort((a, b) => b[1] - a[1])

      let response = `💰 Currency Totals\n\nFound amounts in ${sortedTotals.length} currency${sortedTotals.length === 1 ? '' : '/currencies'}:\n\n`

      sortedTotals.forEach(([currency, total]) => {
        const symbol = getCurrencySymbol(currency)
        response += `${symbol}${total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}\n`
      })

      response += `\n📊 Scanned ${messages.length} messages`

      return response
    }

    // Delivery tracking
    if (lowerQuery.includes('delivery') || lowerQuery.includes('tracking') || lowerQuery.includes('package')) {
      const deliveryMessages = messages.filter(msg => {
        const bodyLower = msg.body.toLowerCase()
        return bodyLower.includes('delivery') ||
          bodyLower.includes('shipped') ||
          bodyLower.includes('tracking') ||
          bodyLower.includes('package') ||
          bodyLower.includes('out for delivery') ||
          bodyLower.includes('delivered') ||
          bodyLower.includes('arriving') ||
          bodyLower.includes('dispatched')
      }).sort((a, b) => b.date - a.date).slice(0, 8)

      if (deliveryMessages.length === 0) {
        return "📦 No delivery or tracking information found in recent messages."
      }

      let response = `📦 Found ${deliveryMessages.length} package(s):\n\n`
      deliveryMessages.forEach((msg, i) => {
        const bodyLower = msg.body.toLowerCase()

        // Determine status
        let status = '📦 Ordered'
        let statusColor = ''
        if (bodyLower.includes('delivered')) {
          status = '✅ Delivered'
        } else if (bodyLower.includes('out for delivery')) {
          status = '🚚 Out for Delivery'
        } else if (bodyLower.includes('arriving today') || bodyLower.includes('arrive today')) {
          status = '🚚 Arriving Today'
        } else if (bodyLower.includes('shipped') || bodyLower.includes('dispatched')) {
          status = '📤 Shipped'
        } else if (bodyLower.includes('in transit') || bodyLower.includes('on the way')) {
          status = '🚛 In Transit'
        }

        // Extract carrier
        const carriers = ['amazon', 'fedex', 'ups', 'usps', 'dhl', 'ontrac', 'lasership']
        let carrier = ''
        for (const c of carriers) {
          if (bodyLower.includes(c) || msg.address.toLowerCase().includes(c)) {
            carrier = c.toUpperCase()
            break
          }
        }

        // Extract merchant
        const merchant = extractMerchantFromMessage(msg.body)

        // Extract tracking number (if visible)
        const trackingMatch = msg.body.match(/(?:tracking|track)[:\s#]*([A-Z0-9]{10,22})/i)
        const tracking = trackingMatch ? trackingMatch[1] : null

        response += `${i + 1}. ${merchant || carrier || 'Package'}\n`
        response += `   ${status}${carrier ? ` • ${carrier}` : ''}\n`
        if (tracking) {
          response += `   Tracking: ${tracking}\n`
        }
        response += `   ${formatDate(msg.date)}\n\n`
      })
      return response.trim()
    }

    // Standard spending queries (general)
    if (lowerQuery.includes('spent') || lowerQuery.includes('spending')) {
      const transactions = parseTransactions

      if (transactions.length === 0) {
        return "📊 No spending transactions found in your messages.\n\nMake sure you have SMS notifications enabled for your bank/payment apps."
      }

      const filtered = applyTimeFilter(transactions, userQuery)
      const total = filtered.reduce((sum, t) => sum + t.amount, 0)
      const currency = filtered[0]?.currency || 'INR'
      const periodLabel = getTimePeriodLabel(userQuery) || 'Total'

      // Group by merchant for top spending
      const merchantTotals = filtered
        .reduce((acc, t) => {
          const merchant = t.merchant || 'Unknown'
          acc[merchant] = (acc[merchant] || 0) + t.amount
          return acc
        }, {} as Record<string, number>)

      const topMerchants = Object.entries(merchantTotals)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 5)

      let response = `💰 Spending Analysis (${periodLabel})\n\n`
      response += `Total Spent: ${formatCurrency(total, currency)}\n`
      response += `Transactions: ${filtered.length}\n`

      if (filtered.length > 0) {
        const average = total / filtered.length
        response += `Average: ${formatCurrency(average, currency)}\n\n`

        if (topMerchants.length > 0) {
          response += `🏪 Top Merchants:\n`
          topMerchants.forEach(([merchant, amount], i) => {
            response += `${i + 1}. ${merchant}: ${formatCurrency(amount, currency)}\n`
          })
        }
      }

      return response
    }

    // Message count
    if (lowerQuery.includes('how many messages') || lowerQuery.includes('message count')) {
      return `📱 You have ${messages.length} messages in total.`
    }

    // Most contacted
    if (lowerQuery.includes('most contact') || lowerQuery.includes('who do i text most')) {
      const contactCounts: Record<string, number> = {}
      messages.forEach(msg => {
        contactCounts[msg.address] = (contactCounts[msg.address] || 0) + 1
      })
      const sorted = Object.entries(contactCounts).sort((a, b) => b[1] - a[1])
      const top3 = sorted.slice(0, 3)
      return `📱 Your most contacted numbers are:\n${top3.map(([number, count]) => `${number}: ${count} messages`).join('\n')}`
    }

    // Default response with more options
    return "I can help you analyze your messages! Try asking:\n\n💰 Spending:\n• \"How much did I spend this month?\"\n• \"Amazon spending\" or \"Amazon transactions\"\n• \"Spent at Swiggy this week\"\n• \"List my transactions\"\n• \"Show spending by category\"\n• \"Top merchants\"\n• \"Show money totals\" or \"Currency totals\"\n\n📅 Bills & Payments:\n• \"Show my upcoming bills\"\n• \"Any payment due?\"\n• \"Credit card due date\"\n\n💳 Account Info:\n• \"What's my account balance?\"\n• \"Show my bank balance\"\n\n📦 Packages:\n• \"Track my packages\"\n• \"Show delivery updates\"\n\n🔐 OTP Codes:\n• \"Find my OTP codes\"\n• \"Show verification codes\"\n\n📊 Statistics:\n• \"How many messages do I have?\"\n• \"Who do I text most?\""
  }

  /**
   * Handles form submission when user sends a query
   * Adds the query to conversation, generates response, and updates state
   *
   * @param e - Form submission event
   */
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!query.trim() || isLoading) return

    const userMessage = query.trim()
    setQuery('')
    setIsLoading(true)

    // Add user message to conversation history
    setConversation(prev => [...prev, { role: 'user', content: userMessage }])

    // Simulate thinking delay for better UX (feels more natural than instant response)
    // Also prevents UI jank from synchronous heavy computation
    const timeoutId = setTimeout(() => {
      const response = generateResponse(userMessage)
      setConversation(prev => [...prev, { role: 'assistant', content: response }])
      setIsLoading(false)
      timeoutsRef.current = timeoutsRef.current.filter((id) => id !== timeoutId)
    }, 500)
    timeoutsRef.current.push(timeoutId)
  }

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-2xl w-full max-w-2xl h-[80vh] flex flex-col relative">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-gray-200 dark:border-gray-700">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-gradient-to-br from-purple-500 to-blue-600 rounded-xl flex items-center justify-center">
              <Brain className="w-6 h-6 text-white" />
            </div>
            <div>
              <h2 className="text-xl font-bold text-gray-900 dark:text-white">AI Assistant</h2>
              <p className="text-sm text-gray-500 dark:text-gray-400">Understands your messages & spending</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {/* New Chat button - show when conversation exists */}
            {conversation.length > 0 && (
              <button
                onClick={() => {
                  setConversation([])
                  setQuery('')
                  setShowDigest(true)
                }}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors text-sm font-medium"
                title="Start new chat"
              >
                <Plus className="w-4 h-4" />
                <span>New Chat</span>
              </button>
            )}
            {onClose && (
              <button
                onClick={onClose}
                className="p-2 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
              >
                <X className="w-5 h-5 text-gray-600 dark:text-gray-400" />
              </button>
            )}
          </div>
        </div>

        {/* Conversation */}
        <div className="flex-1 overflow-y-auto p-6 space-y-4">
          {conversation.length === 0 && (
            <div className="py-4">
              {/* Smart Digest Card */}
              {showDigest && parseTransactions.length > 0 && (
                <div className="mb-6 p-4 rounded-xl bg-gradient-to-br from-blue-50 to-purple-50 dark:from-blue-900/20 dark:to-purple-900/20 border border-blue-200 dark:border-blue-800">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <TrendingUp className="w-5 h-5 text-blue-600 dark:text-blue-400" />
                      <span className="font-semibold text-gray-900 dark:text-white">This Month</span>
                    </div>
                    <button
                      onClick={() => setShowDigest(false)}
                      className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
                    >
                      <X className="w-4 h-4" />
                    </button>
                  </div>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <div className="text-2xl font-bold text-gray-900 dark:text-white">
                        {formatCurrency(generateSmartDigest.totalSpentThisMonth, generateSmartDigest.currency)}
                      </div>
                      <div className="text-sm text-gray-500 dark:text-gray-400 flex items-center gap-1">
                        {generateSmartDigest.spendingChange !== 0 && (
                          <>
                            <span className={generateSmartDigest.spendingChange > 0 ? 'text-red-500' : 'text-green-500'}>
                              {generateSmartDigest.spendingChange > 0 ? '↑' : '↓'}
                              {Math.abs(generateSmartDigest.spendingChange).toFixed(0)}%
                            </span>
                            <span>vs last month</span>
                          </>
                        )}
                      </div>
                    </div>

                    <div className="text-right">
                      <div className="text-2xl font-bold text-gray-900 dark:text-white">
                        {generateSmartDigest.transactionCount}
                      </div>
                      <div className="text-sm text-gray-500 dark:text-gray-400">
                        transactions
                      </div>
                    </div>
                  </div>
                </div>
              )}

              <div className="text-center mb-6">
                <h3 className="text-lg font-semibold text-gray-900 dark:text-white mb-2">How can I help you?</h3>
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  Ask about spending, bills, subscriptions, packages, and more.
                </p>
              </div>

              {/* Quick Action Cards */}
              <div className="grid grid-cols-2 gap-3">
                {QUICK_ACTIONS.map((action, idx) => {
                  const Icon = action.icon
                  const colorClasses: Record<string, string> = {
                    blue: 'bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400',
                    orange: 'bg-orange-50 dark:bg-orange-900/20 text-orange-600 dark:text-orange-400',
                    green: 'bg-green-50 dark:bg-green-900/20 text-green-600 dark:text-green-400',
                    purple: 'bg-purple-50 dark:bg-purple-900/20 text-purple-600 dark:text-purple-400',
                    red: 'bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400',
                    teal: 'bg-teal-50 dark:bg-teal-900/20 text-teal-600 dark:text-teal-400',
                    indigo: 'bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400',
                    cyan: 'bg-cyan-50 dark:bg-cyan-900/20 text-cyan-600 dark:text-cyan-400',
                  }
                  return (
                    <button
                      key={idx}
                      onClick={() => {
                        setQuery(action.query)
                        setShowDigest(false)
                        // Auto-submit the query
                        setConversation(prev => [...prev, { role: 'user', content: action.query }])
                        setIsLoading(true)
                        const timeoutId = setTimeout(() => {
                          const response = generateResponse(action.query)
                          setConversation(prev => [...prev, { role: 'assistant', content: response }])
                          setIsLoading(false)
                          setQuery('')
                        }, 500)
                        timeoutsRef.current.push(timeoutId)
                      }}
                      className="flex items-center gap-3 p-3 rounded-xl bg-white dark:bg-gray-700 border border-gray-200 dark:border-gray-600 hover:border-gray-300 dark:hover:border-gray-500 transition-all hover:shadow-md text-left"
                    >
                      <div className={`w-9 h-9 rounded-lg flex items-center justify-center ${colorClasses[action.color]}`}>
                        <Icon className="w-4 h-4" />
                      </div>
                      <div className="font-medium text-sm text-gray-900 dark:text-white">{action.title}</div>
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          {conversation.map((msg, idx) => (
            <div
              key={idx}
              className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
            >
              <div className={`max-w-[85%] ${msg.role === 'user' ? '' : 'group'}`}>
                <div
                  className={`rounded-2xl px-4 py-3 ${
                    msg.role === 'user'
                      ? 'bg-blue-500 text-white'
                      : 'bg-gray-100 dark:bg-gray-700 text-gray-900 dark:text-white'
                  }`}
                >
                  <p className="whitespace-pre-wrap text-sm">{msg.content}</p>
                </div>

                {/* Copy button and follow-ups for assistant messages */}
                {msg.role === 'assistant' && (
                  <div className="mt-2 flex items-center gap-2">
                    <button
                      onClick={() => {
                        navigator.clipboard.writeText(msg.content)
                        setCopiedIndex(idx)
                        setTimeout(() => setCopiedIndex(null), 2000)
                      }}
                      className="flex items-center gap-1 px-2 py-1 text-xs text-gray-500 hover:text-gray-700 dark:hover:text-gray-300 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors opacity-0 group-hover:opacity-100"
                    >
                      {copiedIndex === idx ? (
                        <>
                          <Check className="w-3 h-3 text-green-500" />
                          <span className="text-green-500">Copied</span>
                        </>
                      ) : (
                        <>
                          <Copy className="w-3 h-3" />
                          <span>Copy</span>
                        </>
                      )}
                    </button>
                  </div>
                )}

                {/* Suggested follow-ups after assistant response */}
                {msg.role === 'assistant' && idx === conversation.length - 1 && !isLoading && (
                  <div className="mt-3 flex flex-wrap gap-2">
                    {getSuggestedFollowUps(msg.content).map((suggestion, sIdx) => (
                      <button
                        key={sIdx}
                        onClick={() => {
                          setConversation(prev => [...prev, { role: 'user', content: suggestion }])
                          setIsLoading(true)
                          const timeoutId = setTimeout(() => {
                            const response = generateResponse(suggestion)
                            setConversation(prev => [...prev, { role: 'assistant', content: response }])
                            setIsLoading(false)
                          }, 500)
                          timeoutsRef.current.push(timeoutId)
                        }}
                        className="px-3 py-1.5 text-xs bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 rounded-full hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors"
                      >
                        {suggestion}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}

          {isLoading && (
            <div className="flex justify-start">
              <div className="bg-gray-100 dark:bg-gray-700 rounded-2xl px-4 py-3">
                <div className="flex gap-2">
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }}></div>
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }}></div>
                  <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }}></div>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Input */}
        <form onSubmit={handleSubmit} className="p-6 border-t border-gray-200 dark:border-gray-700">
          <div className="flex gap-3">
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Ask about your messages or spending..."
              className="flex-1 px-4 py-3 rounded-xl bg-gray-100 dark:bg-gray-700 text-gray-900 dark:text-white placeholder-gray-500 dark:placeholder-gray-400 outline-none focus:ring-2 focus:ring-blue-500"
              disabled={isLoading}
            />
            <button
              type="submit"
              disabled={!query.trim() || isLoading}
              className="px-6 py-3 bg-blue-500 hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-xl flex items-center gap-2 transition-colors"
            >
              <Send className="w-5 h-5" />
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
