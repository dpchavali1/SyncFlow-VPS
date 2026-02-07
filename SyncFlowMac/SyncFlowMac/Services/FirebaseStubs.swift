//
//  FirebaseStubs.swift
//  SyncFlowMac
//
//  Stub implementations for Firebase types during VPS migration.
//  These provide compile-time compatibility while Firebase is removed.
//

import Foundation

// MARK: - Firebase Database Stubs

/// Stub for Firebase DatabaseHandle
public typealias DatabaseHandle = UInt

/// Stub for Firebase DatabaseReference
public class DatabaseReference {
    public func removeObserver(withHandle handle: DatabaseHandle) {
        // No-op stub
    }

    public func child(_ path: String) -> DatabaseReference {
        return self
    }

    public func childByAutoId() -> DatabaseReference {
        return self
    }

    public func setValue(_ value: Any?) {
        // No-op stub
    }

    public func setValue(_ value: Any?, withCompletionBlock: @escaping (Error?, DatabaseReference) -> Void) {
        withCompletionBlock(nil, self)
    }

    public func updateChildValues(_ values: [AnyHashable: Any]) {
        // No-op stub
    }

    public func updateChildValues(_ values: [AnyHashable: Any], withCompletionBlock: @escaping (Error?, DatabaseReference) -> Void) {
        withCompletionBlock(nil, self)
    }

    public func removeValue() {
        // No-op stub
    }

    public func removeValue(completionBlock: @escaping (Error?, DatabaseReference) -> Void) {
        completionBlock(nil, self)
    }

    public func observeSingleEvent(of eventType: DataEventType, with block: @escaping (DataSnapshot) -> Void) {
        block(DataSnapshot())
    }

    public func observeSingleEvent(of eventType: DataEventType, with successBlock: @escaping (DataSnapshot) -> Void, withCancel cancelBlock: ((Error) -> Void)?) {
        successBlock(DataSnapshot())
    }

    public func observe(_ eventType: DataEventType, with block: @escaping (DataSnapshot) -> Void) -> DatabaseHandle {
        return 0
    }

    public func observe(_ eventType: DataEventType, with block: @escaping (DataSnapshot) -> Void, withCancel cancelBlock: ((Error) -> Void)?) -> DatabaseHandle {
        return 0
    }

    public func observe(_ eventType: DataEventType, andPreviousSiblingKeyWith block: @escaping (DataSnapshot, String?) -> Void) -> DatabaseHandle {
        return 0
    }

    public func observe(_ eventType: DataEventType, andPreviousSiblingKeyWith block: @escaping (DataSnapshot, String?) -> Void, withCancel cancelBlock: ((Error) -> Void)?) -> DatabaseHandle {
        return 0
    }

    public func getData(completion: @escaping (Error?, DataSnapshot?) -> Void) {
        completion(nil, DataSnapshot())
    }

    public func getData() async throws -> DataSnapshot {
        return DataSnapshot()
    }

    public func queryOrdered(byChild key: String) -> DatabaseQuery {
        return DatabaseQuery()
    }

    public func queryOrderedByKey() -> DatabaseQuery {
        return DatabaseQuery()
    }

    public func queryLimited(toLast count: UInt) -> DatabaseQuery {
        return DatabaseQuery()
    }

    public func queryLimited(toFirst count: UInt) -> DatabaseQuery {
        return DatabaseQuery()
    }

    public var key: String { return "" }
}

/// Stub for Firebase DatabaseQuery
public class DatabaseQuery {
    public func observe(_ eventType: DataEventType, with block: @escaping (DataSnapshot) -> Void) -> DatabaseHandle {
        return 0
    }

    public func observe(_ eventType: DataEventType, andPreviousSiblingKeyWith block: @escaping (DataSnapshot, String?) -> Void) -> DatabaseHandle {
        return 0
    }

    public func observe(_ eventType: DataEventType, andPreviousSiblingKeyWith block: @escaping (DataSnapshot, String?) -> Void, withCancel cancelBlock: ((Error) -> Void)?) -> DatabaseHandle {
        return 0
    }

    public func observeSingleEvent(of eventType: DataEventType, with block: @escaping (DataSnapshot) -> Void) {
        block(DataSnapshot())
    }

    public func getData(completion: @escaping (Error?, DataSnapshot?) -> Void) {
        completion(nil, DataSnapshot())
    }

    public func getData() async throws -> DataSnapshot {
        return DataSnapshot()
    }

    public func queryLimited(toLast count: UInt) -> DatabaseQuery {
        return self
    }

    public func queryLimited(toFirst count: UInt) -> DatabaseQuery {
        return self
    }

    public func queryOrdered(byChild key: String) -> DatabaseQuery {
        return self
    }

    public func queryStarting(atValue value: Any?) -> DatabaseQuery {
        return self
    }

    public func queryStarting(afterValue value: Any?) -> DatabaseQuery {
        return self
    }

    public func queryEnding(atValue value: Any?) -> DatabaseQuery {
        return self
    }

    public func queryEqual(toValue value: Any?) -> DatabaseQuery {
        return self
    }

    public func queryEqual(toValue value: Any?, childKey: String) -> DatabaseQuery {
        return self
    }

    public func removeObserver(withHandle handle: DatabaseHandle) {
        // No-op
    }
}

/// Stub for Firebase DataEventType
public enum DataEventType {
    case value
    case childAdded
    case childChanged
    case childRemoved
    case childMoved
}

/// Stub for Firebase DataSnapshot
public class DataSnapshot {
    public var value: Any? { return nil }
    public var key: String { return "" }
    public var ref: DatabaseReference { return DatabaseReference() }
    public var children: NSEnumerator { return NSArray().objectEnumerator() }
    public var childrenCount: UInt { return 0 }

    public func exists() -> Bool {
        return false
    }

    public func childSnapshot(forPath path: String) -> DataSnapshot {
        return DataSnapshot()
    }

    public func hasChild(_ childPathString: String) -> Bool {
        return false
    }

    public func hasChildren() -> Bool {
        return false
    }
}

/// Stub for Firebase Database
public class Database {
    public static func database() -> Database {
        return Database()
    }

    public func reference() -> DatabaseReference {
        return DatabaseReference()
    }

    public func reference(withPath path: String) -> DatabaseReference {
        return DatabaseReference()
    }

    public func reference(fromURL url: String) -> DatabaseReference {
        return DatabaseReference()
    }

    public func goOnline() {
        // No-op
    }

    public func goOffline() {
        // No-op
    }

    public var isPersistenceEnabled: Bool {
        get { return false }
        set { }
    }
}

// MARK: - Firebase ServerValue

/// Stub for Firebase ServerValue
public class ServerValue {
    public static func timestamp() -> [String: String] {
        return [".sv": "timestamp"]
    }

    public static func increment(_ delta: Int64) -> [String: Any] {
        return [".sv": ["increment": delta]]
    }

    public static func increment(_ delta: Double) -> [String: Any] {
        return [".sv": ["increment": delta]]
    }

    public static func increment(_ delta: NSNumber) -> [String: Any] {
        return [".sv": ["increment": delta]]
    }
}

// MARK: - Firebase Auth Stubs

/// Stub for Firebase Auth
public class Auth {
    public static func auth() -> Auth {
        return Auth()
    }

    public var currentUser: User? { return nil }

    public func signIn(withCustomToken token: String, completion: ((AuthDataResult?, Error?) -> Void)?) {
        completion?(nil, nil)
    }

    public func signIn(withCustomToken token: String) async throws -> AuthDataResult {
        return AuthDataResult()
    }

    public func signInAnonymously(completion: ((AuthDataResult?, Error?) -> Void)?) {
        completion?(nil, nil)
    }

    public func signInAnonymously() async throws -> AuthDataResult {
        return AuthDataResult()
    }

    public func signOut() throws {
        // No-op
    }

    public func addStateDidChangeListener(_ listener: @escaping (Auth, User?) -> Void) -> AuthStateDidChangeListenerHandle {
        return AuthStateDidChangeListenerHandle()
    }

    public func removeStateDidChangeListener(_ listenerHandle: AuthStateDidChangeListenerHandle) {
        // No-op
    }
}

/// Stub for Firebase User
public class User {
    public var uid: String { return "" }
    public var email: String? { return nil }
    public var displayName: String? { return nil }
    public var isAnonymous: Bool { return true }

    public func getIDToken(completion: @escaping (String?, Error?) -> Void) {
        completion(nil, nil)
    }

    public func getIDTokenResult(completion: @escaping (AuthTokenResult?, Error?) -> Void) {
        completion(nil, nil)
    }

    public func delete(completion: ((Error?) -> Void)?) {
        completion?(nil)
    }
}

/// Stub for Firebase AuthDataResult
public class AuthDataResult {
    public var user: User { return User() }
}

/// Stub for Firebase AuthTokenResult
public class AuthTokenResult {
    public var token: String { return "" }
    public var claims: [String: Any] { return [:] }
}

/// Stub for Firebase AuthStateDidChangeListenerHandle
public class AuthStateDidChangeListenerHandle {}

// MARK: - Firebase Core Stubs

/// Stub for FirebaseApp
public class FirebaseApp {
    public static func configure() {
        // No-op
    }

    public static func configure(options: FirebaseOptions) {
        // No-op
    }

    public static func app() -> FirebaseApp? {
        return nil
    }
}

/// Stub for FirebaseOptions
public class FirebaseOptions {
    public init(googleAppID: String, gcmSenderID: String) {}

    public var apiKey: String? = nil
    public var projectID: String? = nil
    public var storageBucket: String? = nil
    public var databaseURL: String? = nil
}

// MARK: - Firebase Functions Stubs

/// Stub for Firebase Functions
public class Functions {
    public static func functions() -> Functions {
        return Functions()
    }

    public static func functions(region: String) -> Functions {
        return Functions()
    }

    public func httpsCallable(_ name: String) -> HTTPSCallable {
        return HTTPSCallable()
    }

    public func httpsCallable(_ name: String, options: HTTPSCallableOptions) -> HTTPSCallable {
        return HTTPSCallable()
    }
}

/// Stub for HTTPSCallable
public class HTTPSCallable {
    public func call(_ data: Any? = nil, completion: @escaping (HTTPSCallableResult?, Error?) -> Void) {
        completion(nil, nil)
    }

    public func call(_ data: Any? = nil) async throws -> HTTPSCallableResult {
        return HTTPSCallableResult()
    }

    public var timeoutInterval: TimeInterval = 70
}

/// Stub for HTTPSCallableResult
public class HTTPSCallableResult {
    public var data: Any { return [:] }
}

/// Stub for HTTPSCallableOptions
public class HTTPSCallableOptions {
    public init() {}
    public var requireLimitedUseAppCheckTokens: Bool = false
}

/// Stub for FunctionsErrorDetailsKey
public let FunctionsErrorDetailsKey = "details"

/// Stub for FunctionsErrorDomain
public let FunctionsErrorDomain = "com.firebase.functions"

/// Stub for FunctionsErrorCode
public enum FunctionsErrorCode: Int {
    case ok = 0
    case cancelled = 1
    case unknown = 2
    case invalidArgument = 3
    case deadlineExceeded = 4
    case notFound = 5
    case alreadyExists = 6
    case permissionDenied = 7
    case resourceExhausted = 8
    case failedPrecondition = 9
    case aborted = 10
    case outOfRange = 11
    case unimplemented = 12
    case `internal` = 13
    case unavailable = 14
    case dataLoss = 15
    case unauthenticated = 16
}

// MARK: - Firebase Storage Stubs (if needed)

/// Stub for Storage
public class Storage {
    public static func storage() -> Storage {
        return Storage()
    }

    public func reference() -> StorageReference {
        return StorageReference()
    }

    public func reference(withPath path: String) -> StorageReference {
        return StorageReference()
    }
}

/// Stub for StorageReference
public class StorageReference {
    public func child(_ path: String) -> StorageReference {
        return StorageReference()
    }

    public func putData(_ data: Data, metadata: StorageMetadata? = nil, completion: ((StorageMetadata?, Error?) -> Void)? = nil) {
        completion?(nil, nil)
    }

    public func getData(maxSize: Int64, completion: @escaping (Data?, Error?) -> Void) {
        completion(nil, nil)
    }

    public func downloadURL(completion: @escaping (URL?, Error?) -> Void) {
        completion(nil, nil)
    }

    public func delete(completion: ((Error?) -> Void)?) {
        completion?(nil)
    }
}

/// Stub for StorageMetadata
public class StorageMetadata {
    public init() {}
    public var contentType: String?
}
